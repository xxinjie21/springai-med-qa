# 部署手册 — springai-med-qa

> 面向运维 / 交付场景的部署与运维说明。快速开始请见仓库根目录 [`README.md`](../README.md)。

---

## 目录

- [1. 部署拓扑与前置条件](#1-部署拓扑与前置条件)
- [2. 获取镜像](#2-获取镜像)
- [3. Docker Compose 全栈部署](#3-docker-compose-全栈部署)
- [4. 独立部署（外部 MySQL / Redis）](#4-独立部署外部-mysql--redis)
- [5. 环境变量全表](#5-环境变量全表)
- [6. 数据库与索引初始化](#6-数据库与索引初始化)
- [7. 健康检查与探针](#7-健康检查与探针)
- [8. 日志与故障排查](#8-日志与故障排查)
- [9. 备份与回滚](#9-备份与回滚)
- [10. 安全加固清单](#10-安全加固清单)
- [11. 容量与调优建议](#11-容量与调优建议)

---

## 1. 部署拓扑与前置条件

```mermaid
flowchart LR
    CLIENT["患者 / 医生工作台"] --> APP["springai-med-qa<br/>:8080"]
    APP --> MYSQL[("MySQL 8<br/>:3306<br/>med_qa")]
    APP --> REDIS[("Redis Stack<br/>:6379<br/>缓存 + 向量索引")]
    APP --> LLM["LLM / Embedding 网关<br/>OpenAI 兼容"]
    APP --> ACT["/actuator/health"]
```

| 组件 | 版本要求 | 用途 |
|---|---|---|
| Docker + Docker Compose | Docker 24+ / Compose v2 | 全栈编排（推荐方式） |
| JDK | 17（运行 jar 部署时） | 应用运行时 |
| MySQL | 8.0.x | 真相源：16 张 `med_message_{0..15}` 分表 + `med_session` + `med_audit_log` |
| Redis | Redis Stack 7.x（**必须带 RediSearch + RedisJSON**） | 会话缓存、Redisson 锁/限流、向量索引 |
| LLM / Embedding 网关 | OpenAI 兼容协议 | 问诊生成与文档向量化 |

> Redis **必须**是 Redis Stack（或挂载 RediSearch/RedisJSON 模块）。普通 Redis 无法创建向量索引，
> `RedisVectorStore` 初始化会失败。

**MySQL 字符集是硬前置条件，不是可选项（D46）**

Flyway V1 的建表语句**刻意不 pin `ENGINE` / `DEFAULT CHARSET`**——同一份脚本还要跑在 H2 的 MySQL 兼容模式
（测试替身）上，所以它只写可移植的列定义。代价是**库的默认字符集必须由部署方保证为 `utf8mb4`**：

- Compose 部署由两处兜住：`docker-compose.yml` 的 `--character-set-server=utf8mb4
  --collation-server=utf8mb4_unicode_ci`，以及 `docker/mysql/init/01-create-db.sql` 建库时的字符集声明。
- **自建 MySQL 若库默认不是 `utf8mb4`，中文病历会静默存成乱码**——不报错、不告警、健康检查全绿，
  只有医生在问诊记录里看到问号。上线前请核对：

  ```sql
  SHOW VARIABLES LIKE 'character_set_server';   -- 期望 utf8mb4
  SHOW CREATE DATABASE med_qa;                  -- 期望 DEFAULT CHARACTER SET utf8mb4
  ```

- 连接串一侧是另一个陷阱：`characterEncoding` 取的是 **Java** 字符集名，必须写 `UTF-8`。
  写成 MySQL 侧的 `utf8mb4` 会让 Hikari 建池时抛 `UnsupportedEncodingException: utf8mb4`，
  服务连不上库（D33 真机冒烟测试抓到，现由 `ShardingRuleConfigTest` / `MedMigrationPropertiesTest` 守住）。

**H2 留在生产 classpath 上是已记录的权衡（D46）**

`pom.xml` 里 H2 的 scope 是 `runtime` 而**不是** `test`：收窄为 `test` 会让 ShardingSphere-JDBC
在启动时找不到驱动类而失败。它只作为测试替身存在，因此：

- **H2 Web Console 在任何配置文件中都没有开启**，`spring.h2.console.*` 在整个仓库里不存在，也不允许开启——
  那是未鉴权的进程内 SQL 控制台，等于把数据库直接暴露给任何能访问该端口的人；
- 这条约束由 `DeploymentPrerequisiteTest` 直接扫描 Spring 自己的配置文件（`src/main/resources` 下的
  `application*.yml` / `application*.yaml` / `application*.properties`）守住，而不是靠"记得别开"——
  它把 YAML 里的嵌套写法与点号写法归一成同一个属性名，所以换个写法也绕不过去。

**端口占用**

| 端口 | 服务 | 说明 |
|---|---|---|
| `8080` | app | HTTP API / Swagger UI / Actuator |
| `3306` | mysql | 仅本地调试建议映射，生产可不暴露 |
| `6379` | redis-stack | Redis 协议 |
| `8001` | redis-stack | RedisInsight UI，生产建议关闭映射 |

---

## 2. 获取镜像

### 2.1 从 GHCR 拉取（推荐生产）

镜像由 `.github/workflows/docker-publish.yml` 在推送 `v*` tag 时自动构建并发布：

```bash
# 按语义化版本拉取
docker pull ghcr.io/xxinjie21/springai-med-qa:v1.0.0

# 或拉取 main 分支最新构建
docker pull ghcr.io/xxinjie21/springai-med-qa:main
```

发布流程（维护者）：

```bash
git tag v1.0.0
git push origin v1.0.0      # 触发 docker-publish → GHCR
```

### 2.2 本地构建

复用 D27 的多阶段 `Dockerfile`（JDK builder → 分层 JRE runtime，非 root `medqa` 用户）：

```bash
docker build -t springai-med-qa:local .
```

分层结构保证代码变更只重建最小的 `application` 层，依赖层命中缓存。

镜像携带 OCI 元数据（`org.opencontainers.image.*`），发布流水线会把版本号、Git revision 与构建时间
通过 `--build-arg` 写入，因此 `docker inspect` 即可确认手上这个镜像来自哪次提交。

### 2.3 真实构建验证（D33）

单测只能解析 `Dockerfile` 文本，不能证明镜像真的能跑起来。仓库提供一键验证脚本：

```bash
scripts/verify-docker-build.sh                 # 默认 tag springai-med-qa:verify
scripts/verify-docker-build.sh my-registry/med-qa:check
```

脚本会执行一次真实 `docker build`，随后逐项断言运行时契约：

| 断言 | 说明 |
|---|---|
| OCI 标签齐全 | `title` / `source` / `licenses` / `revision` 非空 |
| 非 root 运行 | `Config.User` 必须等于 `medqa` |
| 分层布局 | `BOOT-INF/classes/application.yml`、`BOOT-INF/lib/*.jar`、`org/springframework/boot/loader/**` 均存在 |
| 入口类 | entrypoint 使用 Spring Boot 3.x `JarLauncher`，且该 class 真的在镜像里 |
| 启动冒烟 | 真实启动容器一次，要求 Spring Boot 打出启动横幅或失败分析器横幅（不允许出现 `Could not find or load main class`） |

无 Docker 守护或未安装 Docker 时脚本以退出码 `2` 直接跳过，不会阻断流水线；任一项断言失败以退出码 `1` 失败。
启动冒烟的超时可用环境变量 `BOOT_TIMEOUT`（默认 150 秒）覆盖。

> **这条链路第一次跑就抓到过两个真实缺陷**：
> 1. D27 的 `Dockerfile` 只写了 `extract --layers` 而漏了 `--launcher`，于是 `spring-boot-loader/` 层是空的、
>    镜像里根本没有 `JarLauncher`，容器一启动就 `Could not find or load main class`；
> 2. `sharding/med-sharding.yaml` 的 JDBC URL 写了 `characterEncoding=utf8mb4`。`characterEncoding` 要的是
>    **Java** 字符集名，`utf8mb4` 是 MySQL 服务端字符集，Connector/J 会直接抛
>    `UnsupportedEncodingException: utf8mb4`，导致任何环境下都连不上 MySQL。
>
> 两处都只有「真实构建 + 真实启动」才暴露（纯文本断言当时全绿）。现已修复，并分别加了
> `--launcher` 与「characterEncoding 必须是可被 `Charset` 解析的 Java 字符集名」守护断言。

### 2.4 集成测试阶段与「不许静默跳过」开关（D35）

`ci.yml` 现在有两个互相独立的 job：`verify`（离线全量单测 + JaCoCo 门禁）与 `integration`
（真实 MySQL 8.0 + Redis Stack）。上线前若想在本地复现集成阶段：

```bash
# 本机有 Docker：只跑集成测试
./mvnw test -Dtest='com.med.qa.integration.*IntegrationTest' -DfailIfNoTests=true

# 声明 Docker 必需：Docker 不可用时直接失败，而不是报告 skipped
./mvnw test -Dtest='com.med.qa.integration.*IntegrationTest' \
            -DfailIfNoTests=true -Dmed.test.integration.required=true
```

| 开关 | 形式 | 默认 | 说明 |
|---|---|---|---|
| `med.test.integration.required` | JVM 系统属性（优先级高于环境变量） | 未设置 | `true` / `1` / `yes` / `on` 表示 Docker 必需 |
| `MED_TEST_INTEGRATION_REQUIRED` | 环境变量 | 未设置 | CI 集成阶段置为 `true` |

**排障**：集成阶段报 `Docker is required for the Testcontainers integration suite but no usable
daemon is reachable` 说明 runner 上没有可用 Docker，或 Testcontainers 版本低于 1.21.0
（`TestcontainersVersionTest` 守这条下限）。若本机确实没有 Docker，去掉该开关即可退回「可跳过」。

**镜像层缓存**：集成阶段用 `docker save` / `docker load` 归档 `mysql:8.0.36` 与
`redis/redis-stack:7.4.0-v3` 并交给 `actions/cache` 复用；缓存键里写死了这两个 tag。
升级镜像版本时**必须同时改缓存键**，否则会还原出旧镜像——`CiIntegrationStageConfigTest` 会拦住这种漂移。

### 2.5 RAG 标签检索的真实中间件验证（D36）

`MedRagRetrievalIntegrationTest` 复用同一个 `redis/redis-stack:7.4.0-v3` 镜像，把 RAG 链路接到真实
Redis Stack 上：官方 `RedisVectorStore`、它建出来的索引、写进 Redis 的 JSON 文档、官方 store 翻译的
TAG 过滤，以及 `MedDocumentService` / `MedRetrievalService` / `QuestionAnswerAdvisor`。

唯一被替身顶掉的是 Embedding 网关（`DeterministicEmbeddingModel`）：真实 `EmbeddingModel` 要打外部模型
API，测试环境既没有凭据也无法保证可复现。替身只做「文本 → 向量」，相似度计算、Top-K、排序与过滤求值
仍全部发生在 Redis Stack 里。因此**部署到现场时，RAG 检索行为与这条测试验证的是同一套代码路径**，
但索引里的向量由真实网关生成——两者的差异仅在向量数值本身。

> **这条链路第一次接上真实索引（D36）就抓到一个「检索不可用」的缺陷：标识符未转义。**
>
> RediSearch 的 `TAG` 查询语法保留了一批字符（`-`、`.`、`:` 等），而官方
> `RedisFilterExpressionConverter` 把值原样写进查询串。两种症状都实测到了：
>
> | 场景 | 未转义的结果 |
> |---|---|
> | 等值过滤 `tenant_id == tenant-rag-a` | `Syntax error at offset 24 near a`，该科室全部检索失败 |
> | `IN` 过滤 `patient_id IN [patient-rag-1, __shared__]` | **不报错**，但只返回 `__shared__`——患者自己的病历被静默丢弃 |
>
> 第二种更危险：医生拿到的答案只基于科室指南，而系统没有任何异常信号。现场标识符（UUID、`dept-cardio`
> 这类）几乎必然带连字符，也就是说这条路径此前在真实环境里根本不可用。
> 修复方式是 `MedRetrievalFilters.escapeTagValue`：**只转义查询**，索引里仍写原始标识符，
> 兜底校验 `MedRetrievalFilters.matches` 也仍按原始值比对。
>
> 此前所有离线单测的标识符都是 `hosp1` / `p9001` 这类纯字母数字，恰好绕开了全部保留字符，
> 所以纯文本断言一直是绿的。现由 `MedRetrievalFiltersTest` 的转义用例与
> `MedRagRetrievalIntegrationTest` 的连字符标识符共同守住。

### 2.6 检索质量回归基线（D39）

D36 证明 RAG 链路**能**工作，D37/D38 守住索引这一层；D39 守住真正交付的东西——**检索质量**。同一个问题，
昨天能召回的病历今天还在不在、最相关的那条还在不在第一位、范围内不该出现的文档有没有冒出来。这三类变化
都不会让任何探针变红：服务健康、接口 200、索引存在且 TAG Schema 一致。

金标集固定在 `src/test/resources/rag/retrieval-baseline.json`，语料固定在同名集成测试里，两者一起构成
「这个语料 + 这些问题 = 这些答案」的冻结契约。**改动检索路径（索引拓扑、入库元数据、过滤表达式）后必须重跑它**：

```bash
# 需要 Docker（Testcontainers 起真实 Redis Stack）
./mvnw -Dtest=MedRetrievalBaselineIntegrationTest test

# Windows PowerShell：每个 -D 参数都要加双引号
$env:JAVA_HOME="F:\jdk17"; .\mvnw.cmd "-Dtest=MedRetrievalBaselineIntegrationTest" test
```

失败时的排查顺序：

| 失败形态 | 失败摘要里的关键字 | 先看哪里 |
|---|---|---|
| 召回下降 | `recall ... is below the required ...; missing [...]` | 该用例作用域内是否有文档被过滤条件吃掉；确认 `med.rag.vector-store.metadata-fields` 与入库时写入的元数据键一致 |
| 排序变化 | `the best-ranked document is '...' but '...' was expected` | 换过 Embedding 模型或向量算法（`vector-algorithm` / `distance-metric`）都会改变排序；确认是有意为之再更新金标集 |
| 越权泄漏 | `out-of-scope documents were returned: [...]` | **最高优先级**：过滤表达式或索引 TAG 定义出了问题，等价于 D36 的静默丢弃缺陷，先按 §7.1 检查索引健康 |

> 金标集是**数据**，更新它是正常的迭代动作（加问题、加禁止文档、收紧 `minRecall`）；但一次「因为改了检索
> 实现所以顺手改了期望」的更新，必须同时把 `version` 递增，让 CI 历史里能区分「基线推进」与「基线被迁就」。
>
> 集合本身的结构自检（`MedRetrievalBaselineResourceTest`）离线运行，不需要 Docker：它保证用例都能失败、
> 集合不是空转的，以及租户/科室两个隔离维度都有覆盖。

---

## 3. Docker Compose 全栈部署

仓库根 `docker-compose.yml` 定义了 `redis-stack` + `mysql` + `app` 三服务。

```bash
# 1)（可选）准备 .env 覆盖默认口令与模型凭据
cat > .env <<'EOF'
MED_MYSQL_ROOT_PASSWORD=<强口令>
MED_MYSQL_PASSWORD=<应用账号口令>
OPENAI_BASE_URL=https://your-gateway/v1
OPENAI_API_KEY=<密钥>
MED_EMBEDDING_MODEL=text-embedding-3-small
MED_SECURITY_ENABLED=true
MED_RATE_LIMIT_ENABLED=true
EOF

# 2) 拉起全栈（app 会等待 mysql / redis-stack 健康检查通过后才启动）
docker compose up -d

# 3) 查看状态与日志
docker compose ps
docker compose logs -f app

# 4) 验证
curl -fsS http://localhost:8080/actuator/health

# 5) 停止 / 清栈
docker compose down            # 保留数据卷
docker compose down -v         # 连数据卷一起删除（不可恢复）
```

**Compose 关键契约**

| 约定 | 值 |
|---|---|
| compose project name | `springai-med-qa` |
| app 镜像 | 本地构建 `springai-med-qa:local`（生产替换为 GHCR 镜像） |
| app profile | `SPRING_PROFILES_ACTIVE=prod` |
| 依赖顺序 | `app` → `mysql`(service_healthy) + `redis-stack`(service_healthy) |
| 数据卷 | `mysql-data`、`redis-stack-data` |
| 重启策略 | `unless-stopped` |

> 开箱默认值：Compose 内置 `MED_SECURITY_ENABLED=false`、`MED_SECURITY_DEPT_SCOPE_ENABLED=false`、
> `MED_RATE_LIMIT_ENABLED=false`，便于一键体验。**生产必须显式置为 `true` 并挂载真实密钥。**

---

## 4. 独立部署（外部 MySQL / Redis）

不依赖 Compose 时，直接运行 jar 或镜像，通过环境变量指向既有中间件：

```bash
java -jar target/springai-med-qa-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=prod \
  --server.port=8080
```

或以容器方式：

```bash
docker run -d --name med-qa-app \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e MED_MYSQL_HOST=mysql.internal -e MED_MYSQL_PORT=3306 -e MED_MYSQL_DATABASE=med_qa \
  -e MED_MYSQL_USERNAME=med_qa -e MED_MYSQL_PASSWORD='<pwd>' \
  -e REDIS_HOST=redis.internal -e REDIS_PORT=6379 \
  -e OPENAI_BASE_URL=https://your-gateway/v1 -e OPENAI_API_KEY='<key>' \
  -e MED_SECURITY_ENABLED=true -e MED_RATE_LIMIT_ENABLED=true \
  ghcr.io/xxinjie21/springai-med-qa:v1.0.0
```

Kubernetes 探针可直接使用 Actuator 的探针组（已通过 `management.endpoint.health.probes.enabled` 开启）：

```yaml
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: 8080 }
  initialDelaySeconds: 30
  periodSeconds: 10
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  initialDelaySeconds: 60
  periodSeconds: 15
```

**健康探针的判定口径**

| 端点 | 语义 |
|---|---|
| `/actuator/health` | 聚合状态：MySQL（经 ShardingSphere 数据源执行 `SELECT 1`）与 Redis（`PING`）同时可达才为 `UP` |
| `/actuator/health/liveness` | 进程存活，任一存储不可用时为 `DOWN`（运维可直接看 `redis` / `mysql` 分组件原因；对外 `show-details: never` 不泄露细节） |
| `/actuator/health/readiness` | 同上，用于摘除流量 |
| `/actuator/info` | `components` 版本矩阵：Java / Spring Boot / Spring Framework / Spring AI / ShardingSphere / Redisson / MyBatis / Protobuf，取自 jar manifest，便于现场核对实例版本 |

> 探针只做连通性校验，绝不执行业务 SQL，可安全高频轮询。

---

## 5. 环境变量全表

### 5.1 应用与中间件

| 变量 | 默认 | 说明 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` | 生效 profile，`prod` 为生产调优 |
| `SERVER_PORT` | `8080` | 监听端口 |
| `REDIS_HOST` | `localhost` | 缓存 / 锁 / 限流 / 向量索引共用 |
| `REDIS_PORT` | `6379` | |
| `REDIS_DATABASE` | `0` | |
| `MED_MYSQL_HOST` | `127.0.0.1` | ShardingSphere 数据源主机，同时是 Flyway 迁移目标 |
| `MED_MYSQL_PORT` | `3306` | |
| `MED_MYSQL_DATABASE` | `med_qa` | 由 init 脚本创建 |
| `MED_MYSQL_USERNAME` | `med_qa` | 最小权限应用账号（需有建表权限，迁移要用） |
| `MED_MYSQL_PASSWORD` | `med_qa` | **生产必须覆盖** |
| `MED_MYSQL_ROOT_PASSWORD` | `med_qa_root` | 仅 Compose MySQL 容器使用 |
| `MED_MIGRATION_URL` | 空 | 可选，整串 JDBC URL 覆盖（留空则用上面的 `MED_MYSQL_*` 坐标拼装） |
| `MED_MIGRATION_LOCATIONS` | `classpath:db/migration` | Flyway 迁移脚本位置 |

### 5.2 模型服务

| 变量 | 默认 | 说明 |
|---|---|---|
| `OPENAI_BASE_URL` | `https://api.openai.com` | 兼容任意 OpenAI 协议网关（含院内私有模型服务） |
| `OPENAI_API_KEY` | 空 | **绝不写入仓库**，由环境/密钥管理注入 |
| `OPENAI_EMBEDDINGS_PATH` | `/v1/embeddings` | |
| `MED_EMBEDDING_MODEL` | `text-embedding-3-small` | |
| `MED_EMBEDDING_DIMENSIONS` | `1536` | 必须与索引向量宽度一致，否则装配期 fail-fast |
| `MED_EMBEDDING_MAX_INPUT_TOKENS` | `8191` | 分批嵌入上限 |

### 5.3 安全 / 合规

| 变量 | 默认 | 说明 |
|---|---|---|
| `MED_SECURITY_ENABLED` | `true` | API Key 认证总开关 |
| `MED_SECURITY_REQUIRE_AUTH` | `true` | 缺失密钥时是否拒绝 |
| `MED_SECURITY_DEPT_SCOPE_ENABLED` | `true` | `@RequireDept` 科室权限拦截器 |
| `MED_SECURITY_HEADER` | `X-API-Key` | 认证头，同时决定 Swagger Authorize 头 |
| `MED_RATE_LIMIT_ENABLED` | `true` | 注解式限流开关 |
| `MED_RATE_LIMIT_DEFAULT_RATE` | `10` | 默认令牌数 |
| `MED_RATE_LIMIT_DEFAULT_DURATION` | `1` | 默认窗口（秒） |
| `MED_AUDIT_ENABLED` | `true` | 审计落库；关闭会丢失访问证据，仅限本地 |

### 5.4 存储与会话

| 变量 | 默认 | 说明 |
|---|---|---|
| `MED_CACHE_TTL` | `30m` | 会话缓存 TTL |
| `MED_CACHE_MAX_MESSAGES` | `200` | 缓存窗口消息数（有界缓存，调小只影响缓存，不影响落库轨迹，见 7.6） |
| `MED_CHAT_MAX_MESSAGES` | `20` | 短期记忆窗口：只影响送进模型的消息条数，**不影响落库轨迹**（见 7.6） |
| `MED_CHAT_STREAM_HEARTBEAT` | `15` | SSE 心跳间隔（秒） |
| `MED_CHAT_STREAM_TIMEOUT` | `120` | SSE 超时（秒） |
| `MED_LOCK_WAIT_TIME` | `3s` | 会话锁等待时间 |
| `MED_LOCK_LEASE_TIME` | `0s` | `0` = 看门狗自动续期 |
| `MED_LOCK_WATCHDOG_TIMEOUT` | `30s` | 看门狗超时 |
| `MED_SESSION_DEFAULT_PAGE_SIZE` | `20` | 会话分页默认页大小 |
| `MED_SESSION_MAX_PAGE_SIZE` | `100` | 单页上限 |
| `MED_SESSION_RETENTION_ENABLED` | `false` | 陈旧会话自动归档总开关（见 7.8） |
| `MED_SESSION_RETENTION_DRY_RUN` | `true` | 开启后仍只报告不动作 |
| `MED_SESSION_RETENTION_IDLE_THRESHOLD` | `24h` | 多久未更新算「陈旧」 |
| `MED_SESSION_RETENTION_BATCH_SIZE` | `100` | 每批取多少行 |
| `MED_SESSION_RETENTION_MAX_BATCHES` | `10` | 单次运行最多几批 |
| `MED_SESSION_RETENTION_CHECK_INTERVAL` | `1h` | 两次运行之间的固定延迟 |
| `MED_SESSION_RETENTION_INITIAL_DELAY` | `5m` | 启动后首次运行的延迟 |
| `MED_SESSION_RETENTION_LOCK_WAIT` | `5s` | 抢占集群互斥锁的等待时间 |
| `MED_SESSION_RETENTION_LOCK_LEASE` | `5m` | 互斥锁租约；`0` = 看门狗续期 |
| `MED_SESSION_ARCHIVE_ENABLED` | `false` | 已归档会话轨迹冷归档导出总开关（见 7.9） |
| `MED_SESSION_ARCHIVE_DRY_RUN` | `true` | 开启后仍只报告不拷贝 |
| `MED_SESSION_ARCHIVE_BATCH_SIZE` | `50` | 每批取多少会话 |
| `MED_SESSION_ARCHIVE_MAX_BATCHES` | `5` | 单次运行最多几批 |
| `MED_SESSION_ARCHIVE_CHECK_INTERVAL` | `6h` | 两次运行之间的固定延迟 |
| `MED_SESSION_ARCHIVE_INITIAL_DELAY` | `10m` | 启动后首次运行的延迟 |
| `MED_SESSION_ARCHIVE_LOCK_WAIT` | `5s` | 抢占集群互斥锁的等待时间 |
| `MED_SESSION_ARCHIVE_LOCK_LEASE` | `5m` | 互斥锁租约；`0` = 看门狗续期 |
| `MED_SESSION_PURGE_ENABLED` | `false` | 已认证冷副本的热分表清理总开关（见 7.11） |
| `MED_SESSION_PURGE_DRY_RUN` | `true` | 开启后仍只报告不删除（但走完整校验） |
| `MED_SESSION_PURGE_MIN_ARCHIVED_AGE` | `7d` | 冷却窗口：多久没更新才允许清理，必须 > 0 |
| `MED_SESSION_PURGE_BATCH_SIZE` | `50` | 每批取多少会话 |
| `MED_SESSION_PURGE_MAX_BATCHES` | `5` | 单次运行最多几批 |
| `MED_SESSION_PURGE_CHECK_INTERVAL` | `12h` | 两次运行之间的固定延迟 |
| `MED_SESSION_PURGE_INITIAL_DELAY` | `15m` | 启动后首次运行的延迟 |
| `MED_SESSION_PURGE_LOCK_WAIT` | `5s` | 抢占集群互斥锁的等待时间 |
| `MED_SESSION_PURGE_LOCK_LEASE` | `5m` | 互斥锁租约；`0` = 看门狗续期 |
| `MED_SESSION_RECLAIM_ENABLED` | `false` | 孤儿冷副本回收总开关（见 7.13） |
| `MED_SESSION_RECLAIM_DRY_RUN` | `true` | 开启后仍只报告不删除（但走完整校验） |
| `MED_SESSION_RECLAIM_BATCH_SIZE` | `50` | 每批取多少会话 |
| `MED_SESSION_RECLAIM_MAX_BATCHES` | `5` | 单次运行最多几批 |
| `MED_SESSION_RECLAIM_CHECK_INTERVAL` | `12h` | 两次运行之间的固定延迟 |
| `MED_SESSION_RECLAIM_INITIAL_DELAY` | `15m` | 启动后首次运行的延迟 |
| `MED_SESSION_RECLAIM_LOCK_WAIT` | `5s` | 抢占集群互斥锁的等待时间 |
| `MED_SESSION_RECLAIM_LOCK_LEASE` | `5m` | 互斥锁租约；`0` = 看门狗续期 |

### 5.5 RAG

| 变量 | 默认 | 说明 |
|---|---|---|
| `MED_RAG_INDEX_NAME` | `med-doc-index` | RediSearch 索引名 |
| `MED_RAG_KEY_PREFIX` | `med:doc:` | 禁止与 `med:chat:` 命名空间重叠 |
| `MED_RAG_VECTOR_ALGORITHM` | `HNSW` | 小语料可切 `FLAT` 做精确检索 |
| `MED_RAG_INITIALIZE_SCHEMA` | `true` | 启动时建索引 |
| `MED_RAG_TOP_K` / `MED_RAG_MAX_TOP_K` | `4` / `50` | 单次召回上限 |
| `MED_RAG_SIMILARITY_THRESHOLD` | `0.0` | `0` = 接受全部，避免"指南看起来不存在" |
| `MED_RAG_MAX_QUERY_LENGTH` | `1000` | 查询长度上限 |
| `MED_RAG_INGEST_BATCH_SIZE` | `25` | 入库批处理大小 |
| `MED_RAG_INGEST_MAX_CONTENT_LENGTH` | `20000` | 单文档字符上限 |
| `MED_RAG_INGEST_MAX_DOCUMENTS` | `500` | 单次请求文档数上限 |
| `MED_RAG_ADVISOR_ORDER` | `1` | `QuestionAnswerAdvisor` 链序 |
| `MED_RAG_INDEX_ENABLED` | `true` | 向量索引健康探针开关（**非 Redis Stack 部署必须置 `false`**，否则 Pod 会被判为不健康） |
| `MED_RAG_INDEX_CHECK_INTERVAL` / `MED_RAG_INDEX_INITIAL_DELAY` | `5m` / `1m` | 索引探针轮询间隔与启动宽限期 |
| `MED_RAG_INDEX_REBUILD_ENABLED` | `false` | 受控索引重建开关（D38）。**默认关闭**：这是唯一能删除搜索索引的代码路径，需要时再显式打开 |
| `MED_RAG_INDEX_REBUILD_ALLOW_DELETE` | `false` | 是否允许 `DROP_AND_REINGEST`（连已入库文档一起删）。与上一项独立，默认拒绝 |
| `MED_RAG_INDEX_REBUILD_BATCH_SIZE` | `50` | 重建回写批大小；不得超过 `MED_RAG_INGEST_MAX_DOCUMENTS`（矛盾在启动时报错） |
| `MED_RAG_INDEX_REBUILD_MAX_DOCUMENTS` | `5000` | 单次重建语料上限，超出即拒绝（避免误触发全量重嵌入） |
| `MED_RAG_INDEX_REBUILD_LOCK_WAIT` / `..._LOCK_LEASE` | `5s` / `10m` | 重建互斥等待与租约；租约 `0` 表示交给 Redisson 看门狗续期 |

---

## 6. 数据库与索引初始化

| 层次 | 归属 | 内容 |
|---|---|---|
| 数据库 + 账号 | `docker/mysql/init/01-create-db.sql`（Compose 首次启动执行一次，幂等） | 建 `med_qa` 库（utf8mb4）、建 `med_qa@%` / `med_qa@localhost` 账号并授权 |
| 表结构 | Flyway V1–V6（应用启动时执行，**直连物理 MySQL**） | V1：`med_message_{0..15}` 16 张分表；V2：`med_session`；V3：`med_audit_log`；V4：`med_session` 的保留策略索引 `idx_med_session_retention (status, updated_at)`（D47，见 7.8）；V5：冷归档表 `med_message_archive` + `med_session_archive`（D48，见 7.9）；V6：`med_session` 的清理标记 `purged_at`（D50，见 7.11） |
| 向量索引 | Spring AI `RedisVectorStore`（`initialize-schema=true`） | RediSearch 索引 `med-doc-index`，TAG 字段 `tenant_id` / `dept_id` / `patient_id` |

分片规则见 `src/main/resources/sharding/med-sharding.yaml`：
`med_message` 按 `session_id` 经 `MED_CRC32_MOD` 算法插件路由到 16 张物理表；
`med_session` / `med_audit_log` / `med_message_archive` / `med_session_archive` 走 `!SINGLE` 规则落在同一数据源。

> **冷归档表为什么显式写进 `!SINGLE`（D48）**：`med_message_archive` 与 `med_session_archive` 是
> **刻意不分片**的（一次会话的轨迹是一条连续范围读，而不是散在 16 张分表里），把它写进规则就是把这
> 个决定写下来——否则下一位读者会以为「漏了分片规则」而顺手补上。

> 独立部署（不用 Compose）时，请自行执行 `docker/mysql/init/01-create-db.sql` 等价语句；
> 表结构无需手工建，Flyway 会迁移。

> **字符集（D46）**：V1 的 DDL 不含 `ENGINE` / `DEFAULT CHARSET`，所以**库的默认字符集必须是
> `utf8mb4`**（见第 1 节）。独立部署时，等价建库语句里也要显式写
> `CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`，否则中文病历会**静默**乱码——这是
> 「不报错、不告警、健康检查全绿」的一类故障，只能靠上线前核对字符集变量发现。

**迁移为什么直连物理 MySQL，而不是走 `spring.datasource`：** `spring.datasource` 指向的是
ShardingSphere 代理，它把 `med_message_0..15` 这 16 张物理分表**藏起来**只暴露逻辑表
`med_message`；而 V1 迁移脚本要建的正是这 16 张物理表。经代理执行会连续失败两次：
Flyway 先通过代理读 `information_schema` 判断库不存在、于是执行 `CREATE DATABASE`，被 MySQL
以 `1007 Can't create database ... database exists` 拒绝；即便关掉建库，第一条 `CREATE TABLE`
也会以 `Load actual table metadata 'med_message_0' failed` 中断。
因此 Boot 自带的 `FlywayAutoConfiguration` 在 `spring.autoconfigure.exclude` 中被排除，
改由 `config/MedFlywayConfig` 用一套**独立的迁移连接池**（`med.storage.migration.*`，
坐标默认与 `MED_MYSQL_*` 同源）执行迁移；该连接池**刻意不注册为 `DataSource` Bean**——
一旦容器里出现第二个 `DataSource`，MyBatis 的 `@ConditionalOnSingleCandidate(DataSource.class)`
会失去唯一候选，`SqlSessionTemplate` 无法装配，整个上下文启动失败。

- 迁移开关就是 `spring.flyway.enabled`（默认 `true`，离线测试置 `false`）；
- 迁移在上下文刷新期执行，**失败即启动失败**，不会带着未迁移的 schema 对外提供服务；
- 只增不改：回滚到旧版本前请确认旧版本能兼容当前 schema。

> **这条链路第一次真正跑起来（D34）就抓到三个问题**，在此之前它们对构建完全不可见：
> 1. 依赖里只有 `flyway-core`。Flyway 10 把各数据库支持拆成了独立模块，缺 `flyway-mysql`
>    时迁移直接 `FlywayException: Unsupported Database: MySQL 8.0`，服务连不上库；
> 2. 迁移原本走 Boot 自带的 `FlywayAutoConfiguration`，也就是走 ShardingSphere 代理，于是
>    撞上上面的 `1007` / `Load actual table metadata` 两个失败；
> 3. Testcontainers 1.20.6 使用的 Docker API 版本回退值低于当前 Docker Engine 的下限，
>    导致 `DockerAvailableCondition` 判定"本机没有 Docker"，**D30 集成测试整套被静默跳过**——
>    前两个问题正是因此长期藏在"全绿"的构建里。现已把 Testcontainers 升到 1.21.x，
>    并由 `TestcontainersVersionTest` 守住版本下限，防止再次退化成"静默跳过"。
>
> 三处都只有「真实启动 + 真实中间件」才暴露（纯文本断言当时全绿），
> 现由 `MedFlywayConfigTest` 与 `MedProductionStartupIntegrationTest` 覆盖。

---

## 7. 健康检查与探针

| 检查 | 命令 | 期望 |
|---|---|---|
| 应用存活 | `curl -fsS http://localhost:8080/actuator/health` | HTTP 200 |
| Compose 服务状态 | `docker compose ps` | `app` / `mysql` / `redis-stack` 均 `healthy` |
| 向量索引就绪 | `docker exec med-qa-redis-stack redis-cli FT.INFO med-doc-index` | 返回索引信息 |
| 指标暴露 | `curl -fsS http://localhost:8080/actuator/prometheus` | Prometheus 文本格式指标 |
| API 文档 | 浏览器打开 `http://localhost:8080/swagger-ui.html` | 可看到 chat / session / rag 三组 |

Actuator 暴露范围由 `application.yml` 控制：`management.endpoints.web.exposure.include=health,info,prometheus`，
`show-details=never`（不向外部泄露细节）。

> **D40：profile 里的暴露列表是「覆盖」而不是「合并」。** Spring Boot 对 list 属性不做合并——只要
> `application-prod.yml` 重新声明了 `management.endpoints.web.exposure.include`，base 里那一份就被整段替换。
> 而 `Dockerfile`（`ENV SPRING_PROFILES_ACTIVE="prod"`）与 `docker-compose.yml`（`SPRING_PROFILES_ACTIVE: prod`）
> 激活的都是 `prod`，所以**生产环境的暴露列表实际由 `application-prod.yml` 决定**。此前该文件写的是
> `include: health,info`，于是 `/actuator/prometheus` 在生产环境返回 404，Prometheus 抓不到
> `med_qa_alert_total`，`deploy/prometheus/med-qa-alerts.yml` 里**全部**告警规则永不触发——整条可观测性
> 链路静默失效，而服务看起来一切正常。现已修正为 `health,info,prometheus`，并由跨文件契约测试
> `ApplicationProfileContractTest` 守住：它用 Spring Boot 自己的 `YamlPropertySourceLoader` 按 profile
> 优先级合并配置、再用 `Binder` 绑定**实际生效值**，断言「任何 profile 都不得移除 base 已暴露的端点」，
> 同时锁定 profile 集合与镜像/compose 实际激活的 profile，避免断言因 profile 改名而空转。
> 改动任一 profile 文件前请先跑 `mvnw.cmd test "-Dtest=ApplicationProfileContractTest"`。

健康组件（`/actuator/health`，`show-details=never` 时只暴露聚合状态，组件明细需 `show-details: always` 才可见）：

| 组件 | 判定 |
|---|---|
| `med-storage` | `mysql` / `redis` 各自连通性，明细值 `available` |
| `med-vector-index`（D37） | RAG 向量索引的存在性与 TAG 字段一致性，`reason` ∈ `index-missing` / `schema-drift` / `unreachable` |

### 7.1 向量索引健康与 Schema 漂移（D37）

`med-storage` 只回答「Redis 通不通」。**Redis 通 ≠ 检索可用**：索引可以被手工删掉，或因为实例不是
Redis Stack 版而从未建成功；也可以存在、但 TAG 字段与配置漂移（字段改名后旧索引没跟着变）。
两种情况都**不抛异常**——接口照样 200，只是召回变少——所以任何连通性探针都发现不了。

`MedVectorIndexProbe` 通过官方 Jedis 的 `FT.LIST` / `FT.INFO` 读出索引的存在性、文档数、扫描前缀
与 TAG 字段（**只读元数据**，不做任何向量或检索计算），`MedVectorIndexHealthIndicator` 据此给出：

| `reason` | 触发条件 | 现场处置 |
|---|---|---|
| `index-missing` | `FT.LIST` 未报告索引 | 确认 Redis 是 Redis Stack 版；用 `RagAdminController` 重新入库或执行 D38 的重建 |
| `schema-drift` | 索引未声明 `med.rag.index.expected-tag-fields` 中的 TAG 字段 | 比对 `med.rag.vector-store.metadata-fields`，重建索引 |
| `unreachable` | 探针本身抛异常 | 先按 Redis 故障处理，此时索引结论不可信 |

> `med.rag.index.expected-tag-fields`（默认 `tenant_id` / `dept_id` / `patient_id`）必须与
> `med.rag.vector-store.metadata-fields` 中的 `TAG` 项保持一致——探针只能发现「你让它去找」的漂移。
> 只监控、不重建：重建属 D38，探针永远不会写 Redis。

`MedVectorIndexAlertMonitor` 复用 `med.alert.*` 链路推送：降级 `WARNING`（`rag-index-degraded`）、
恢复 `INFO`（`rag-index-recovered`）、探测失败 `CRITICAL`（`rag-index-probe-failed`）。
Prometheus 规则 `MedQaRagIndexDegraded` 消费 `med_qa_alert_total{code="rag-index-degraded"}`。

> **`unreachable` 与另外两种 `reason` 不是同一件事（D43）**：`index-missing` / `schema-drift` 是
> 「探针跑完了，结论是索引需要修」，所以只报 `WARNING`；`unreachable` 是「探针根本没拿到结论」，
> 按 `CRITICAL` 处理并触发 `MedQaRagIndexProbeFailed` 规则。这个分流必须做在
> `MedVectorIndexHealthIndicator` 里——`AbstractHealthIndicator#health()` 是 `final` 且把异常吞成
> 普通 `DOWN`，调用方无法用 `try/catch` 区分「探针挂了」与「索引坏了」。详见 §7.5。

### 7.2 索引重建（D38）

D37 只监控、不重建；D38 补上受控的修复动作。`MedVectorIndexRebuilder` 把「删索引 → 按当前配置重建 → 回写语料
→ 校验」变成一次带分布式互斥、带验收、带报告的操作，全部复用官方组件：

| 步骤 | 复用组件 |
|---|---|
| 互斥 | Redisson `RLock`，键 `med:lock:rag:index:rebuild:{index}` |
| 删除 | 官方 Jedis `FT.DROPINDEX`（保留文档）或 `FT.DROPINDEX … DD`（连文档一起删） |
| 重建 Schema | `RedisVectorStore#afterPropertiesSet()`，即官方生命周期钩子，由 `med.rag.vector-store.*` 发 `FT.CREATE` |
| 回写 | `MedDocumentService.ingestAll`（普通入库路径，Embedding 与 TAG 打标一致） |
| 验收 | `MedVectorIndexProbe`：重建成功的判据与 `/actuator/health` 相同 |

两种模式：

| 模式 | 动作 | 用途 / 风险 |
|---|---|---|
| `INDEX_ONLY`（默认） | 删索引、**保留文档** | 修复 Schema 漂移。重建时 RediSearch 会重新索引前缀下已存在的 JSON 文档，因此**不丢文档、不调 Embedding** |
| `DROP_AND_REINGEST` | 删索引 + 删文档，再回写调用方给的语料 | 语料损坏/替换。**破坏性**，需要 `MED_RAG_INDEX_REBUILD_ALLOW_DELETE=true`，否则请求在接触 Redis 之前就被拒绝 |

```bash
# 打开重建能力（默认关闭）
MED_RAG_INDEX_REBUILD_ENABLED=true
# 仅在确实要整体替换语料时才打开第二把钥匙
MED_RAG_INDEX_REBUILD_ALLOW_DELETE=false
# 批大小不得超过 MED_RAG_INGEST_MAX_DOCUMENTS（默认 50 vs 500），矛盾会在启动时直接失败
MED_RAG_INDEX_REBUILD_BATCH_SIZE=50
# 单次重建语料上限与互斥等待/租约
MED_RAG_INDEX_REBUILD_MAX_DOCUMENTS=5000
MED_RAG_INDEX_REBUILD_LOCK_WAIT=5s
MED_RAG_INDEX_REBUILD_LOCK_LEASE=10m
```

现场处置（对应 `MedVectorIndexHealthIndicator` 的三种 `reason`）：

| `reason` | 动作 |
|---|---|
| `index-missing` | 先确认 Redis 是 Redis Stack 版；再以 `INDEX_ONLY` 重建（索引不存在时它就是「建索引」，不会删除任何东西） |
| `schema-drift` | 用 `INDEX_ONLY` 重建：删掉旧索引、按当前 `med.rag.vector-store.metadata-fields` 重新 `FT.CREATE`，已入库文档会被自动重新索引，**无需重新入库** |
| `unreachable` | 按 Redis 故障处理；此时不要重建（连不上就无法加锁，重建会直接以 `failed` 收尾） |

结果与告警：

- `MedIndexRebuildReport.outcome` ∈ `completed` / `verification-failed` / `failed` / `skipped-lock-held` / `refused`，
  并带 `documentsBefore → documentsAfter`、回写条数、批次数与耗时；
- 告警码：`rag-index-rebuild-completed`（INFO）、`rag-index-rebuild-skipped`（WARNING，含被拒绝）、
  `rag-index-rebuild-failed`（CRITICAL）；
- Prometheus 规则 `MedQaRagIndexRebuildFailed`（`severity: critical`）：重建失败可能让索引消失或为空，
  比它要修的漂移更糟，因此直接呼叫值班；
- 进度：每个阶段与每个批次发布一个 `MedIndexRebuildProgress` 快照（`currentProgress()`），
  只含计数与阶段名，可安全打日志。

> **重建不经 HTTP 暴露**：回写语料需要语料本身，而语料不在本服务库里（文档只存在于向量索引中）。
> 触发方式由调用方决定；同时这也避免了在 `RagAdminController` 的授权边界修复前扩大攻击面。
> **重建前请先确认 `med.rag.vector-store.initialize-schema=true`**：置 `false` 时没有任何东西会在删除后
> 重建索引，该组合会在启动时直接失败，而不是等到重建时才报错。

### 7.3 告警链路（D33）

Actuator 只能回答「有人来问」时的健康状态。`com.med.qa.alert` 补上了推送侧：

```
MedStorageAlertMonitor（@Scheduled 轮询既有 MedStorageHealthIndicator）
MedVectorIndexAlertMonitor（@Scheduled 轮询 MedVectorIndexHealthIndicator，D37）
        └─> MedAlertNotifier（策略过滤 + Redisson TTL Map 去重）
                ├─> LoggingMedAlertSink   → 应用日志（key=value 结构化行）
                └─> MetricsMedAlertSink   → med_qa_alert_total（Micrometer）
```

- **策略**：`med.alert.*`（开关、轮询间隔、冷却窗口、最低级别、静音码），全部可在 `.env` 覆盖；
- **去重**：以 `code:component` 为指纹写入 `med:alert:dedupe`（Redisson `RMapCache`，TTL = cooldown），
  多副本共享同一个抑制窗口，一次故障只响一次。**指纹在「至少一个 sink 接收成功」之后才写**（D43，
  见 §7.5）——先写再投会把「可能失败的投递」标成已送达；
- **失败开放**：去重存储不可达时**照常派发**——因为 Redis 挂掉本身就是需要告警的场景；
- **恢复通知**：组件恢复会补一条 `INFO`（`storage-recovered` / `rag-index-recovered`），
  避免「还在坏」与「早已恢复」无法区分。

### 7.4 监控栈（可选）

`docker-compose.yml` 中 Prometheus 与 Alertmanager 位于 `observability` profile，默认**不启动**：

```bash
docker compose --profile observability up -d
# Prometheus  UI: http://localhost:9090   （Targets / Rules / Alerts）
# Alertmanager UI: http://localhost:9093
```

| 文件 | 作用 |
|---|---|
| `deploy/prometheus/prometheus.yml` | 抓取 `app:8080/actuator/prometheus`，15s 间隔，加载规则并指向 Alertmanager |
| `deploy/prometheus/med-qa-alerts.yml` | 规则：`MedQaTargetDown` / `MedQaStorageUnavailable` / `MedQaStorageProbeFailed` / `MedQaRagIndexDegraded` / `MedQaRagIndexProbeFailed` / `MedQaRagIndexRebuildFailed` / `MedQaAlertStorm` / `MedQaHighServerErrorRate` / `MedQaConsultationLatencyHigh` / `MedQaRateLimitStorm` |
| `deploy/alertmanager/alertmanager.yml` | 按 `alertname` + `component` 分组；`critical` 走独立接收器与更短的重复间隔；抑制规则避免「整体不可达」时重复刷屏 |

> **必须替换**：`alertmanager.yml` 中的 `webhook_configs.url` 是占位地址（Alertmanager 不支持在配置里
> 展开环境变量）。接入企业微信 / 钉钉 / PagerDuty 时改成真实中继地址即可；未替换前告警仍可在
> Alertmanager UI 看到，只是不会推到手机上。

自定义应用指标：`med_qa_alert_total{severity,code,component}`（计数器）与
`med_qa_alert_last_epoch_seconds`（最近一次**告警**的时间戳）。

> **它不是 dead-man's switch（D53 修正）**：D33 起这里的原文写着「可用于 dead-man's-switch 规则」，
> 但那条规则从来不存在，而且这个 gauge 的语义也做不到这件事——它回答的是「上次**告警**是什么时候」，
> 而死信开关要回答的是「上次**评估**是什么时候」。一个运行正常的医院**不产生告警**，基于它的开关会为
> 一次正确的部署永久误报；四个后台作业在无事可做时又**刻意保持沉默**，所以「空转的作业」与「死掉的作业」
> 在这里完全一样。真正的死信开关见 §7.14。

### 7.5 告警投递顺序与探测失败信号（D43）

告警链路有两处「注释承诺的行为与代码实际行为相反」的缺陷，共同点是**不会让任何测试变红**，
只会让告警在最需要它的时刻消失。两处都在 D43 收敛：

| 缺陷 | 现场表现 | 修法与排查方式 |
|---|---|---|
| 先写冷却指纹、再投递（P1-5） | `MedAlertNotifier` 原先用 `putIfAbsent` 的返回值同时回答「之前投过吗」与「现在就占位」。所有 sink 同时失败时（日志卷满、指标采集器不可达），指纹已经落进 `med:alert:dedupe`，该告警在**整个冷却窗口内被静默丢弃**——恰恰是告警最该出现的场景 | 判定改为**只读**（`RMapCache#containsKey`，Redisson 在 Lua 里一并判断条目 TTL），指纹只在 `dispatch` 返回「至少一个 sink 接受」**之后**才写。现场排查：如果某个故障持续存在却只在日志里出现过一次，先用 `redis-cli --scan --pattern 'med:alert:dedupe*'` 看指纹是否被提前写入 |
| `INDEX_PROBE_FAILED` 分支不可达（P1-6） | `AbstractHealthIndicator#health()` 是 `final` 且把异常吞成 `DOWN`，所以「用 `try/catch` 包住 `health()`」在生产路径上永不触发。Redis 全挂时监控只报 `WARNING` 的 `rag-index-degraded`，与「索引可修复」共用同一级别，**关键故障被降级成普通告警** | 把「探测本身失败」做成**显式信号**：`reason=unreachable` 由 `MedVectorIndexHealthIndicator` 自己写入（它包住了 `probe()` 调用），`MedVectorIndexAlertMonitor.isProbeFailure` 读该 `reason` 分流。现场排查：`curl -s localhost:8080/actuator/health \| jq '.components."med-vector-index".details.reason'` —— `unreachable` 按 Redis 故障处理，`index-missing` / `schema-drift` 按索引修复处理 |

对应的 Prometheus 规则：`rag-index-degraded` → `MedQaRagIndexDegraded`（warning），
`rag-index-probe-failed` → `MedQaRagIndexProbeFailed`（critical）。两者必须是两条规则，
否则「Redis 全挂」会被合并进「索引漂移」的告警里。

跨组件契约由 `AlertDeliveryContractTest` 守住（真实探针 → 健康组件 → 监控 → 通知器 → sink 整条链），
断言 Redis 不可达时出来的是 `CRITICAL` 的 `rag-index-probe-failed`、索引只是缺失时仍是 `WARNING`、
以及「全部 sink 失败 → 指纹未写 → 下一次轮询重新告警」。按项目惯例这条守卫**先被亲眼看过它变红**：
还原两处缺陷后共 6 个用例失败（3 个测试类）。

### 7.6 会话轨迹的持久化契约（D44）

三层存储各管一段，**不要把它们混为一谈**：

| 层 | 保存什么 | 边界 | 调优变量 |
|---|---|---|---|
| MySQL `med_message_{crc32(session_id)%16}` | **完整病历轨迹**，每轮只增不删 | 无上限 | — |
| Redis `med:chat:{tenant}:{dept}:{session}` | 最近一段消息（未命中回源 MySQL） | `MED_CACHE_MAX_MESSAGES` | `MED_CACHE_TTL` / `MED_CACHE_MAX_MESSAGES` |
| `MessageWindowChatMemory` | 送进模型的最近消息 | `MED_CHAT_MAX_MESSAGES` | `MED_CHAT_MAX_MESSAGES` |

**`MED_CHAT_MAX_MESSAGES` 只影响送进模型的消息条数，不影响落库轨迹。** D1–D43 的写路径是
「先 `deleteSession` 再 `appendAll`」，而 Spring AI 的 `saveAll` 交进来的正是裁剪后的窗口，于是每写
一轮都会把窗口外的历史消息物理删除——一场几十轮的会诊在库里只剩最后 20 条。D44 改为
`insertIfAbsent`（`INSERT … ON DUPLICATE KEY UPDATE message_id = message_id`）的幂等追加，
`MedChatMemoryRepository.saveWindow` 不再有任何删除路径，写完后把该窗口发布到 Redis 缓存；
冷缓存回源时按 `MED_CACHE_MAX_MESSAGES` 截断，避免完整轨迹被整段灌进模型 prompt。

运维含义：

- 调小 `MED_CHAT_MAX_MESSAGES` 只会让模型少看几轮上下文，**不会**删数据；调小
  `MED_CACHE_MAX_MESSAGES` 只会让缓存窗口变窄，读未命中时回源 MySQL。
- 写路径由 `med:lock:chat:{tenant}:{dept}:{session}` 串行化，并发轮次会以 `SESSION_LOCKED`
  （业务码 `40900`）快速失败；看到该码说明同一会话真的在被并发写入，而不是数据损坏。
- **患者归属来自会话**：`med_message.patient_id` 是 `NOT NULL`，而 Spring AI 的消息不带任何项目身份
  （患者 id 不在会话 id 里，助手消息由框架构造），所以桥接层写入前会用会话行解析患者；会话不存在或
  没有患者时以 `STORAGE_ERROR` 拒绝写入。**排查**：若日志出现
  `Column 'patient_id' cannot be null` 或 `does not exist, so its messages cannot be attributed`，
  说明写入的会话在 `med_session` 里查不到——先确认会话是否真的创建过、是否被清理过。
- 全量轨迹核查（绕开 ShardingSphere，直接看物理分表）：

  ```bash
  # 按会话统计真实行数：分片 = crc32(session_id) % 16
  docker compose exec mysql mysql -umed_qa -pmed_qa med_qa \
    -e "SELECT COUNT(*) FROM med_message_7 WHERE session_id='<sessionId>';"
  ```

  若该计数小于医生实际看到的轮数，说明有人在 D44 之前删过数据（历史行为），新写入不再有此问题。
- 跨组件契约：`ChatMemoryWindowIntegrationTest`（离线，窗口 2 条 vs 轨迹 5 条）与
  `MedStorageAndLockIntegrationTest#memoryWindowTrimsButTranscriptKeepsEveryTurn`（真实 MySQL +
  Redis Stack，窗口 4 条 vs 分表 10 行）。
- **口径与代码注释同源（D46）**：这三层的行为写在三个类的 javadoc 里，改行为时请一并改注释——
  `MedChatMemoryRepository`（`findAll` 返回的是有界窗口、`reload` 是全仓库唯一返回完整轨迹的读）、
  `RedisMessageCache#windowSize()`（读路径回源时的截断上界）、
  `MedCacheProperties`（`med.cache.max-messages` 只约束缓存，不约束轨迹）。
  `reload` 虽然返回全量，但它回填的缓存仍会被原生 `LTRIM` 裁到窗口内；
  而 **`MED_CACHE_MAX_MESSAGES=0` 会同时解除缓存截断与回源截断**，等于把整段历史交给模型 prompt——
  这是运维风险，不是常规调优项。

### 7.7 隔离标签不进入向量文本（D45）

`spring.ai.openai.embedding.metadata-mode` 必须是 **`NONE`**，这不是调优项而是硬要求：

- Spring AI 1.0.0 的 `OpenAiEmbeddingModel.embed(Document)` 先按该模式格式化文档
  （`Document#getFormattedContent(MetadataMode)`），而 `DefaultContentFormatter` 把 `EMBED` 解释为
  「**全部元数据键** 减去 `excludedEmbedMetadataKeys`」。也就是说 `EMBED` 会把 `tenant_id` /
  `dept_id` / `patient_id` 拼进送进 embedding 接口的文本——相似度被标签污染，隔离标签本身也被写进向量。
- `EMBED` 还是 Spring AI 自己 `OpenAiEmbeddingProperties.metadataMode` 字段的**默认值**。所以
  **「这个键没配」不等于安全**：把配置项清空或删掉，行为会退回 `EMBED`。代码侧的回落值
  （`EmbeddingModelConfig.SAFE_METADATA_MODE`）同样收敛为 `NONE`，使「没配」fail-safe。
- 该键**刻意不做成环境变量**：部署方不应该有能力把隔离标签重新塞回向量里。
- 关掉 `EMBED` **不影响过滤**：标签仍然留在文档的 JSON 值里，RediSearch 仍按
  `med.rag.vector-store.metadata-fields` 把三个标签索引成 `TAG` 字段，`med.rag.index.expected-tag-fields`
  仍与之一致（否则 D37 的探针会把重建好的索引误报为 `schema-drift`）。

运维含义：

- **不要**为了「提升检索质量」把它调回 `EMBED`。若真的怀疑检索质量下降，用 D39 的金标集
  （`MedRetrievalBaseline*`）量化，而不是动这个开关——调回 `EMBED` 只会让相似度被标签污染，
  并且把科室/患者标识写进向量库。
- 排查命令（`/actuator/env` 刻意未暴露，因此直接读镜像里的配置）：

  ```bash
  docker compose exec app sh -c "grep -A2 'metadata-mode' /app/BOOT-INF/classes/application.yml"
  ```

  输出应为 `metadata-mode: NONE`；若为 `EMBED` 或该键缺失，说明镜像里的配置被改过或退回框架默认值。
- 跨组件契约：`EmbeddingMetadataContractTest` 用 `YamlPropertySourceLoader` + `Binder` 绑定**实际生效值**
  （逐 profile 叠加），并用真实的 Spring AI formatter 演示同一个 `Document` 在 `NONE` 与 `EMBED` 下
  嵌入文本的差别，同时断言守卫会拒绝 `EMBED` 与「属性缺失」两种情形。

### 7.8 会话保留策略（D47）

`MedChatSessionService#archiveSession` 的注释从 D20 起就写着会诊有两种结束方式——显式关闭，或
**保留策略作业扫描陈旧会话**——但直到 D47 这个作业才真正存在。在那之前 `ARCHIVED` 只能由一次显式
API 调用到达：患者开了会话问一句就再也不回来，那一行会永远停在 `ACTIVE`，它的 Redis 窗口也会在
TTL 到期后每次读都从 MySQL 重新灌满，`med_session` 只增不减。

D47 的作业把「`ACTIVE` 且超过 `MED_SESSION_RETENTION_IDLE_THRESHOLD` 未被更新」的会话转为
`ARCHIVED`，并顺手丢掉它的缓存窗口。**它不删任何消息**：归档只是状态迁移 + 缓存驱逐，轨迹仍在分表里，
误归档可以改回来。

两把钥匙，都默认保守（与 D38 的索引重建同源）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `MED_SESSION_RETENTION_ENABLED` | `false` | 总开关；关闭时作业与调度器都不存在，定时器不碰 `med_session` |
| `MED_SESSION_RETENTION_DRY_RUN` | `true` | **开启能力后仍然只报告不动作**：先读一次报告，确认阈值合理再关掉它 |
| `MED_SESSION_RETENTION_IDLE_THRESHOLD` | `24h` | 多久没动过算「陈旧」；必须明显大于最长的一次真实会诊 |
| `MED_SESSION_RETENTION_BATCH_SIZE` | `100` | 每批取多少行 |
| `MED_SESSION_RETENTION_MAX_BATCHES` | `10` | 单次运行最多几批（`batch-size × max-batches` = 单次最多触碰多少会话） |
| `MED_SESSION_RETENTION_CHECK_INTERVAL` | `1h` | 两次运行之间的间隔（固定延迟，不是固定频率：慢的一轮不会叠上下一轮） |
| `MED_SESSION_RETENTION_INITIAL_DELAY` | `5m` | 启动后多久开始第一轮 |
| `MED_SESSION_RETENTION_LOCK_WAIT` | `5s` | 抢集群互斥锁的等待时间 |
| `MED_SESSION_RETENTION_LOCK_LEASE` | `5m` | 互斥锁租约；`0` = 交给 Redisson 看门狗续期 |

**为什么自动归档不会误伤正在进行的会诊**——两条彼此独立的机制，都不依赖「作业跑得够快」：

1. **陈旧判据与状态迁移在同一条 SQL 里**。归档语句是
   `UPDATE med_session SET status=ARCHIVED, updated_at=? WHERE session_id=? AND status=ACTIVE AND updated_at <= ?`。
   作业先查候选、再逐个归档，中间患者完全可能回来问下一句；把
   `updated_at <= 截止时间` 放进 `WHERE`，数据库会直接拒绝这行（返回 0 行），而不是先读一次再写一次。
   该语句由 `ChatSessionMapper#updateStatusIfStale` 承载，跨组件契约测试
   `ChatSessionMapperRetentionShardingTest#updateStatusIfStaleRefusesARefreshedSession` 钉住它——
   把谓词从 SQL 里去掉，这条测试立刻变红。
2. **归档在会话锁内执行**（`med:lock:chat:{tenant}:{dept}:{session}`，与消息写入路径同一把锁）。
   锁被占用（说明有轮次正在写）按 `skipped` 计数，**不算失败**——忙着的会话就是活着的会话。

**为什么不复用 `archiveSession`**：请求态的 `PatientAccessGuard` 对**无主体**调用 fail-closed
（D21 的设计），后台作业恰恰没有主体，复用会让每个候选都以 `FORBIDDEN` 被拒。反向的捷径同样危险：
在 `MedChatSessionService` 上加一个「跳过守卫」的方法，等于给所有请求态路径发一把无守卫的归档原语。
所以 `MedSessionRetentionService` 自带一条系统态归档路径，只复用真正承载不变量的协作者
（mapper 的 compare-and-set、会话锁、缓存驱逐），并把理由写在类注释里。

**运维含义**：

- **单实例调试**：先 `MED_SESSION_RETENTION_ENABLED=true` + 保持 `DRY_RUN=true`，看一轮日志
  （`dry run: session ... would be archived`）确认阈值；再关掉 `DRY_RUN` 真正归档。
- **集群**：多个副本同时开启是安全的。抢到 `med:lock:session:retention` 的副本才动手，其余副本
  报 `skipped-lock-held`（这是互斥生效，不是故障）。**不要**把 `MED_SESSION_RETENTION_LOCK_WAIT`
  调大——抢不到就该让位，不该排队堆积。
- **积压量**：报告里的 `remaining` 是运行结束时仍陈旧的会话数；若每轮都非 0，说明
  `batch-size × max-batches` 追不上积压，调大 `MAX_BATCHES`（或缩短 `CHECK_INTERVAL`）。
- **失败**：单个候选失败不会中断整轮，会计入 `failed` 并出现在 `failures` 里（`BizException` 按
  `ErrorCode` 渲染，例如 `STORAGE_ERROR`）。整轮失败（例如 Redis 不可达拿不到互斥锁）会以
  `session-retention-failed`（WARNING）推送告警——**不 page**，因为会诊本身仍然正常，代价只是
  `med_session` 持续增长；但也不能静默，因为「作业停了」和「医院没有废弃会话」看起来完全一样。
- **排查**：直接查有多少陈旧会话，无需等作业：

  ```sql
  -- 直接连物理 MySQL（绕开 ShardingSphere），status: ACTIVE=0
  SELECT COUNT(*) FROM med_session
  WHERE status = 0 AND updated_at <= (UNIX_TIMESTAMP() * 1000 - 24 * 3600 * 1000);
  ```

  该查询走 V4 迁移建的 `idx_med_session_retention (status, updated_at)`；没有这个索引就是全表扫描，
  这也是 V4 存在的原因。
- 跨组件契约：`MedSessionRetentionServiceTest`（锁被占时不碰 MySQL、dry-run 零写入、CAS 返回 0 记
  `skipped`）、`ChatSessionMapperRetentionShardingTest`（真实 DDL 下三条语句的谓词与状态码映射）、
  `MedSessionRetentionConfigTest`（开关真的增删 Bean，且 `@Scheduled` 的占位符键在
  `application.yml` 里真实存在——否则作业会静默退回注解里的字面默认值，运维改配置不生效）。

---

### 7.9 冷归档导出（D48）

7.8 的作业把陈旧会话的状态翻成 `ARCHIVED` 并丢掉它的 Redis 窗口，但**轨迹一行没动**：它仍躺在 16 张
`med_message_*` 热分表里。也就是说「这个会话已归档」这句话在存储层没有任何可验证的凭据——既没有冷副本，
也没有一份能证明冷副本完整的摘要。

D48 补上这一半：把已归档会话的**完整轨迹**按统一存储规范（Protobuf 二进制）复制到**非分片**冷表
`med_message_archive`，并在 `med_session_archive` 写一行**导出清单**。存的是冻结的 Protobuf 字节，因此
异构 Python 中间件用自己的 `med_session.proto` 就能解——这是路线图第四节「两套系统可互读互迁」承诺的
第一个可执行落点。

| 表 | 内容 | 主键 |
|---|---|---|
| `med_message_archive` | 每条消息一行：`session_id` / `message_id` / 租户科室患者 / `created_at` / `archived_at` / `payload`（冻结的 Protobuf 字节，`LONGBLOB`） | `(session_id, message_id)` |
| `med_session_archive` | 每个已导出会话一行（导出清单）：`message_count` / `payload_checksum` / `exported_at` | `session_id` |

> **为什么冷表不分片**：一次会话的轨迹是一条连续范围读（`idx_med_message_archive_session`），而不是
> 散在 16 张分表里的散射查询。分片规则存在的意义是让**在线问诊的写入路径**便宜，冷数据没有写入路径。

**校验和怎么算（`SessionArchiveChecksum`）**：逐条按 `message_id \t created_at \t sha256hex(payload)`
组成规范行，用 `\n` 连接后再做一次 SHA-256，得到 64 位小写十六进制。空轨迹的摘要是 `sha256("")`
（`e3b0c442…`），与「从未导出」区分得开——后者由清单行的**存在与否**表达。Python 侧不需要本项目的任何
代码，按同一份规范就能复算出同一个摘要。

两把钥匙，都默认保守（与 D38 的索引重建、D47 的保留策略同源）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `MED_SESSION_ARCHIVE_ENABLED` | `false` | 总开关；关闭时导出与调度器都不存在，定时器不碰数据库 |
| `MED_SESSION_ARCHIVE_DRY_RUN` | `true` | **开启能力后仍然只报告不拷贝**：先读一次报告，确认范围合理再关掉它 |
| `MED_SESSION_ARCHIVE_BATCH_SIZE` | `50` | 每批取多少会话 |
| `MED_SESSION_ARCHIVE_MAX_BATCHES` | `5` | 单次运行最多几批（`batch-size × max-batches` = 单次最多导出多少会话） |
| `MED_SESSION_ARCHIVE_CHECK_INTERVAL` | `6h` | 两次运行之间的间隔（固定延迟，不是固定频率） |
| `MED_SESSION_ARCHIVE_INITIAL_DELAY` | `10m` | 启动后多久开始第一轮 |
| `MED_SESSION_ARCHIVE_LOCK_WAIT` | `5s` | 抢集群互斥锁的等待时间 |
| `MED_SESSION_ARCHIVE_LOCK_LEASE` | `5m` | 互斥锁租约；`0` = 交给 Redisson 看门狗续期 |

**凭什么相信这份冷副本**——清单只在拿到证据之后才写，两条检查都在写清单之前：

1. **源轨迹在拷贝期间没动过**。轨迹先读一遍用于建副本，写完冷表后再读一遍，两次的摘要必须一致，否则
   **拒绝写清单**。支撑这条不变量的是「`ARCHIVED` 是终态且写入路径每轮问诊前都过
   `MedChatSessionService#requireWritableSession`（`SessionStatus#isWritable()` 只对 `ACTIVE` 为真）」——
   但这条不变量**属于另一个组件**，所以导出不靠「相信它」：将来若有人让归档会话重新可写，这里会以
   `mismatched` 报警，而不是留下一份静默过期的冷副本。
2. **冷副本能复现源摘要**。冷表读回来按同一规范重算摘要，必须等于源摘要。这一步才把「INSERT 返回 1」
   变成「现在躺在冷表里的字节就是轨迹里的字节」。

**拒绝认证不是死路**：行是**先拷贝、后认证**的，而拷贝是幂等的（主键 `(session_id, message_id)` +
`ON DUPLICATE KEY UPDATE message_id = message_id`）。被拒绝的会话没有清单，因此**下一轮仍是候选**，
只补缺的那几条消息然后重新认证——重试会收敛。

> **本迭代不做删除**：导出只增不删，热分表一行不动，冷表也没有删除路径。7.8 的类注释承诺「误归档可以
> 改回来」，删掉热行会让这句话失效；真正冷热分层的「清」需要自己的守卫与自己的迭代，本阶段没有给它编号。

**运维含义**：

- **单实例调试**：先 `MED_SESSION_ARCHIVE_ENABLED=true` + 保持 `DRY_RUN=true`，看一轮日志
  （`dry run: transcript of archived session ... would be exported`）；再关掉 `DRY_RUN` 真正拷贝。
- **集群**：多个副本同时开启是安全的。抢到 `med:lock:session:archive:export` 的副本才动手，其余副本报
  `skipped-lock-held`（互斥生效，不是故障）。
- **积压量**：报告里的 `remaining` 是运行结束时仍没有清单的已归档会话数；若每轮都非 0，说明
  `batch-size × max-batches` 追不上积压，调大 `MAX_BATCHES`（或缩短 `CHECK_INTERVAL`）。
- **复核单个会话**：`MedSessionArchiveExportService#verify(sessionId)` 返回
  `VERIFIED` / `MANIFEST_MISSING` / `TRANSCRIPT_CHANGED` / `COLD_COPY_DIFFERS`。前两个是「导出没发生」，
  第三个是「源在导出后又动过」（重新导出即可，作业会自己做），**第四个才指向冷表本身**——它才是需要
  人去查冷存储的信号。该方法刻意**不挂任何控制器**：它按 sessionId 读轨迹、不带租户维度，不能交给
  请求态调用者（与 D41/D42 收敛掉的那类缺口同源）。
- **直接查积压，无需等作业**：

  ```sql
  -- 直接连物理 MySQL（绕开 ShardingSphere），status: ACTIVE=0 / CLOSED=1 / ARCHIVED=2
  SELECT COUNT(*) FROM med_session s
  WHERE s.status = 2
    AND NOT EXISTS (SELECT 1 FROM med_session_archive a WHERE a.session_id = s.session_id);
  ```

  候选查询正是这条语句（`SessionArchiveMapper#selectUnexportedArchivedSessions`）。**为什么把
  `NOT EXISTS` 放进 SQL**：若改成「按 `updated_at` 翻一页再逐行问有没有清单」，最旧的那批恰好就是已
  导出的那批，作业会永远原地打转、永远够不到后面的积压。
- **告警**：`session-archive-completed`（INFO，只在真的导出了东西时发）、`session-archive-mismatch`
  （WARNING，有副本被拒绝认证）、`session-archive-failed`（WARNING，整轮失败）。三者都不 page：导出只
  增不删，任何一条触发时临床数据都完好无损；但也都不能静默——**「作业停了」和「医院没有可归档的会话」
  外观完全一样**，而「有副本没通过校验」与「作业正常跟上了」同样外观一致。
- 跨组件契约：`MedSessionArchiveExportServiceTest`（锁被占时不碰 MySQL、dry-run 零写入、源在拷贝中途
  变化/冷副本摘要不符时**拒绝写清单**、单会话失败不中断整轮）、`SessionArchiveMapperShardingTest`
  （真实 DDL 下 V5 建表、`NOT EXISTS` 候选查询排除已导出会话、`insertIfAbsent` 幂等、BLOB 逐字节往返、
  清单 CAS）、`MedSessionArchiveConfigTest`（开关真的增删 Bean，且 `@Scheduled` 的占位符键在
  `application.yml` 里真实存在）。

### 7.10 归档轨迹的读取（D49）

**先记住一句话**：D49 之前，「归档」在应用侧等于「消失」。归档会 evict Redis 窗口，而服务里没有任何
代码路径能把一个会话的消息读回来——`ChatController` 只追加新的一轮，`SessionController` 只有
create / get / close / archive / list，`verify` 读冷表只为算摘要且刻意不对请求态开放。运维看到的现象是
「`med_session` 有这一行、`status=ARCHIVED`，但接口拿不到会诊内容」。

**读路径只有一个入口**：`GET /api/sessions/{sessionId}/transcript?tenantId=&deptId=`，内部是
`MedTranscriptService#read`。响应体同时给出两个运维要用的字段：

| 字段 | 含义 |
|---|---|
| `source` | `HOT` = 来自 16 张 `med_message_*` 热分表；`COLD` = 来自 `med_message_archive` 冷副本 |
| `checksum` | 返回这段轨迹的**规范摘要**，与 `med_session_archive.payload_checksum` 同一口径 |

**读哪一份不是「先热后冷」的经验规则，而是一次可重算的摘要比较**：

1. 会话不是 `ARCHIVED`，或它**没有**导出清单 → 热分表是唯一副本，返回 `HOT`。这就是没开
   `MED_SESSION_ARCHIVE_ENABLED` 的部署的常态，读路径不会因为「没开归档」而拒读。
2. 有清单时，重算热轨迹摘要；等于清单里的 `payload_checksum` → 热行仍是那份被认证的轨迹，返回 `HOT`。
3. 否则读冷副本并按同一规范重算；等于清单摘要 → 返回 `COLD`。这覆盖两种形态：热行已被清空，以及
   **删到一半、热行残缺**——D50 的清理不需要在读路径里再加分支。
4. 两份都对不上 → HTTP `200` + 业务码 `50301`（`STORAGE_ERROR`）**拒绝返回**。这是刻意的：返回热行等于
   交付档案说「不是这份」的字节，返回空轨迹等于把数据丢失报成「这次会诊是空的」。

**排障动作**（都直连物理 MySQL，绕开 ShardingSphere）：

```sql
-- 1) 这个会话有没有被认证过（status: ACTIVE=0 / CLOSED=1 / ARCHIVED=2）
SELECT session_id, status, updated_at FROM med_session WHERE session_id = '<id>';
SELECT message_count, payload_checksum, exported_at FROM med_session_archive WHERE session_id = '<id>';

-- 2) 热分表里还剩几条（分片号 = crc32(session_id) % 16，与统一存储规范一致）
SELECT COUNT(*) FROM med_message_<shard> WHERE session_id = '<id>';

-- 3) 冷副本里几条
SELECT COUNT(*) FROM med_message_archive WHERE session_id = '<id>';
```

- 接口返回 `50301` 且第 1 步有清单、第 3 步条数与 `message_count` 不一致 → **冷副本被改动或丢失**，
  这是数据完整性问题：不要手工补写冷表，先按 §9 从备份恢复，再重新入库该会话的轨迹。
- 接口返回 `source=COLD` 而第 2 步还有行 → 热行与清单对不上（残缺或被改），读路径已自动改走冷副本；
  该信号说明有东西在绕过写入路径动热表，先查审计日志（`med_audit_log`）。
- 接口 `404`（业务码 `40400`）→ 会话不存在，或 `tenantId`/`deptId` 与行不匹配（**跨科室一律按不存在上报**，
  这是 D21 的隔离语义，不是故障）；`403`（`40300`）→ 患者读的不是自己的会话。
- **读路径没有开关**，也不该有：能被关掉的读路径等于给运维发一把「静默 404」的钥匙。它同样**不加锁**——
  写入路径每轮重发整窗且逐条幂等（D44），读到的永远是完整行。
- 跨组件契约：`MedTranscriptServiceTest`（热读不碰归档表、归档但未导出仍读热表、热行残缺时回落冷副本、
  两份都对不上时拒绝、坏 Protobuf 载荷拒绝而非跳过）、`SessionTranscriptTest`、`TranscriptDigestsTest`、
  `TranscriptResponseTest` / `MessageResponseTest`，以及 `MedStorageAndLockIntegrationTest` 在真实 MySQL 上
  验证「把热行删干净后仍能按 `med_session.proto` 读回同一段轨迹」。

### 7.11 热分表清理（D50）

**先记住一句话**：D48 的冷归档**只增不删**。会话一旦被导出并认证，它的轨迹就同时存在于两处——16 张
`med_message_*` 热分表和 `med_message_archive` 冷副本——而**热分表那一份永远不会自己消失**。D44 之后
热表只增不删是刻意的（写入路径每轮重发整窗、逐条幂等，靠的就是「重发即自愈」），代价是热分表随会诊量
单调增长。D50 补上这最后一步：**只删被认证冷副本覆盖的那部分热行**。

**这不是「按状态删」而是「按摘要删」**。一个会话要被清理，必须依次通过三道校验，**顺序不可交换**：

| 顺序 | 校验 | 不过时 |
|---|---|---|
| 1 | `med_session_archive` 里有导出清单，取出 `payload_checksum` | 判 `refused`：没人认证过这个会话，热行是唯一副本 |
| 2 | 重算**冷副本**摘要，必须等于清单摘要 | 判 `refused`：冷副本被改动过，先按 §9 恢复再谈清理 |
| 3 | 重算**热轨迹**摘要，必须等于同一份清单摘要 | 判 `refused`：热行与清单对不上（残缺或被改），它承载着没人认证过的字节 |

**为什么冷副本必须先于热轨迹被验证**：在第 2 步通过之前，热行仍然是这个会话**唯一**被认证的副本。
如果先读热行、发现它「等于清单」就删掉，那么一旦冷副本其实已经损坏，删除动作就把唯一的好副本也毁了。
先证明「冷的那份是好的」，再证明「热的那份和冷的那份一样」，最后才允许删。

**为什么热轨迹摘要不符也要拒绝、而不是「反正要删了」**：热行比清单多出来的那些消息，是清单**认证
不到**的字节。删掉它们等于承认「认证范围之外的数据可以静默丢弃」——而这正是 D48 存在的理由。宁可
`refused` 让运维来看，也不要删掉一段没人认证过的轨迹。

**`purged_at` 落在 `med_session` 上，不落在冷表上**（`V6__add_med_session_purged_at.sql`）。三个理由：

- **候选查询要能收敛**。清理的候选谓词是 `status = ARCHIVED AND purged_at IS NULL AND EXISTS(清单)
  AND updated_at <= <冷却线>`。若不落标记，每次运行都会重新捞到同一批「热行已删空」的会话——D48 已经
  在 `NOT EXISTS` 上吃过一次这个亏（分页后再过滤会永远翻不到下一页），这里用标记把「已退休」写死。
- **冷表是证据，不是状态**。冷行是档案，往档案里写「我已经被删了」这种运行态字段，会让「重新导出以
  修复」这条路径多一个要清理的脏状态。
- **标记是 CAS**。`ChatSessionMapper#markPurged` 把 `status = ?` 与 `purged_at IS NULL` 放进同一条
  UPDATE 的 `WHERE` 里，与 D47 的 `updateStatusIfStale` 同形：并发运行（或人工归档）与本次清理撞车时，
  只有一方能写成功。

**先删热行、后写标记**，顺序同样不可交换。中断只会留下「热行已空、`purged_at` 仍为 NULL」——下次运行
把它当候选再捞一次，重算热轨迹得 0 条，直接补上标记即可收敛（`purgeOne` 里 `hot.isEmpty()` 那条分支
就是为这个形态写的）。反过来「先标记后删」一旦中断，这个会话就永远不再被任何一次运行看见，热行留在
那里没人清——**状态机必须是「可重复的删除」而不是「一次性的标记」**。

**冷却窗口 `min-archived-age`（默认 `7d`）**：只有 `updated_at` 早于「现在 − 冷却窗口」的会话才会被
清理。D47 承诺过「误归档的会话可以搬回来」，而搬回来的前提是**它还在热分表里**。没有冷却窗口的话，
一个刚被误归档、又刚被导出认证的会话，可能在运维发现之前就被清空了热行。窗口只按
`med_session.updated_at` 判断，不看导出时间——导出时间只证明「拷过了」，不证明「没人还在意它」。

**三个保守旋钮，缺一不可**：

| 旋钮 | 默认 | 含义 |
|---|---|---|
| `MED_SESSION_PURGE_ENABLED` | `false` | 总开关。关闭时 `MedSessionPurgeService` / `MedSessionPurgeScheduler` **不存在**，定时器不碰数据库 |
| `MED_SESSION_PURGE_DRY_RUN` | `true` | 开启后仍只报告不删除；**dry-run 只扫一批**，但**走完整的三道校验** |
| `MED_SESSION_PURGE_MIN_ARCHIVED_AGE` | `7d` | 冷却窗口，必须严格大于 0 |

`dry-run` 走完整校验是有意的：它要回答的不是「有多少候选」，而是「**有多少候选会被拒绝**」。运维正是
靠这个数字决定要不要真的打开删除——若 dry-run 报告里 `refused > 0`，说明有副本对不上，先别 arm。

**集群互斥**：`RLock` 键 `med:lock:session:purge`（不可配置）。抢不到锁的运行记
`SKIPPED_LOCK_HELD` 并立刻返回，**不是失败**（同一轮里另一个实例已经在扫）。**每个候选取会话锁**（D51
补上，见 7.12）：原实现刻意不拿，理由是候选都是 `ARCHIVED`、写入路径拒写，而摘要比对才是发现撕裂读的
手段——但**搬回是第一个能离开 `ARCHIVED` 的写者，而摘要比对看不见它**。现在候选在锁内处理、并在锁内
`selectById` 重读状态，非 `ARCHIVED` 记 `skipped` 零改动，锁忙同样记 `skipped`。Redis 不可达时**向上抛**
（`STORAGE_ERROR`），不静默跳过。

**只删 `med_message` 热分表**。`med_message_archive` / `med_session_archive` 在 D50 里**一个字节都不动**
（本迭代没有任何删除冷数据的路径）。这也是为什么清理作业**不复用** D49 的读路径：读路径会在两份都对
不上时拒绝返回，而清理需要的是一份**可判定的拒绝理由**，两者对「不确定」的处理方式不同。

**排障动作**（都直连物理 MySQL，绕开 ShardingSphere）：

```sql
-- 1) 还有多少会话待清理（status: ACTIVE=0 / CLOSED=1 / ARCHIVED=2）
SELECT COUNT(*) FROM med_session s
WHERE s.status = 2
  AND s.purged_at IS NULL
  AND s.updated_at <= <now - 冷却窗口>
  AND EXISTS (SELECT 1 FROM med_session_archive a WHERE a.session_id = s.session_id);

-- 2) 某个会话到底是「已退休」还是「漏了」
SELECT session_id, status, updated_at, purged_at FROM med_session WHERE session_id = '<id>';

-- 3) 它还剩几条热行（分片号 = crc32(session_id) % 16）
SELECT COUNT(*) FROM med_message_<shard> WHERE session_id = '<id>';
-- 4) 冷副本条数（应等于 med_session_archive.message_count，且恒不因清理而变）
SELECT COUNT(*) FROM med_message_archive WHERE session_id = '<id>';
```

- 第 2 步 `purged_at` 有值、第 3 步却还有行 → **有东西绕过了清理路径往热表写**（或写回在删之后发生）。
  先查审计日志（`med_audit_log`）；读路径此时仍会返回 `source=HOT`，临床数据完好。
- 第 2 步 `purged_at` 为空、第 3 步已为 0 → 上一次运行删完热行后在写标记前中断，下次运行会自动收敛，
  无需人工干预。
- 作业报 `refused > 0` → 见上文三道校验；先跑一遍 D49 的读路径（`GET .../transcript`）看 `source` 与
  `checksum`，再决定是恢复冷副本还是重新导出。
- **告警**：`session-purge-completed`（INFO，只在真的删了东西时发）、`session-purge-refused`（WARNING，
  有候选被拒绝认证，**在完成通知之前独立发出**）、`session-purge-failed`（WARNING，整轮失败）。
  三者都不 page：清理只删「已有认证冷副本」的热行，任何一条触发时临床数据都还在冷表里。但
  **`-refused` 绝不能静默**：被拒绝的会话外观与「还没轮到清理」完全一样，而它实际意味着有副本已经对
  不上号了。
- 跨组件契约：`MedSessionPurgeServiceTest`（锁被占时不碰 MySQL、dry-run 零删除、清单缺失/冷副本不符/
  热轨迹不符三种拒绝、删完热行后补标记、单会话失败不中断整轮）、`SessionPurgeMapperShardingTest`
  （真实 DDL 下 V6 加列、候选查询排除 `purged_at` 非空的会话、`markPurged` 的 CAS 语义、按会话删除
  只命中一张分片）、`MedSessionPurgeConfigTest`（开关真的增删 Bean，且 `@Scheduled` 的占位符键在
  `application.yml` 里真实存在），以及 `MedStorageAndLockIntegrationTest` 在真实 MySQL + Redis 上验证
  「认证过的会话被清空热行后，D49 的读路径改答 `COLD`，且冷表逐字节未变」。

### 7.12 误归档会话的搬回（D51）

D47 / D48 / D50 的文档都在引用同一句承诺——「误归档的会话可以搬回来」——但直到 D51 才真的有代码实现它。
端点是：

```
POST /api/sessions/{sessionId}/restore?tenantId=<tenant>&deptId=<dept>
```

**它做什么**：把 `ARCHIVED` 的会话搬回 `ACTIVE`（重新接受消息），并**撤销**它的导出清单。授权完全复用
`MedChatSessionService#getSession`：跨租户/科室按「不存在」上报（`40400`），患者越权 `40300`，状态迁移在
`med:lock:chat:{tenant}:{dept}:{session}` 会话锁内完成。

**它要求什么**（三道检查，缺一即拒绝 `40000`）：

1. 会话必须是 `ARCHIVED`——别的状态不需要搬回；
2. `med_session.purged_at` 必须为 `NULL`——热行已被 7.11 的清理作业回收的会话，**活副本已经不存在**；
3. 有导出清单时，热轨迹的摘要必须复现清单的 `payload_checksum`。

**它刻意不做什么**：**不重建热行**。`med_message_archive` → `med_message` 的回灌会让「归档」变成可逆的
物理操作，而 7.11 的冷静期正是为「回收**之前**可以搬回」而设。因此 **`min-archived-age` 不是延迟，它就是
搬回窗口**：窗口内搬回可行，窗口外（热行已被回收）搬回被拒，但会诊记录**仍然可读**——用 D49 的
`GET /api/sessions/{sessionId}/transcript` 读，`source` 会是 `COLD`。运维看到 `40000` 且消息里带
`retired` 时，正确的动作是**读冷副本**，不是手工往热表插行。

**顺序不可交换**：先撤销清单（`SessionArchiveMapper#deleteManifest`），再翻状态。中断在两步之间留下
「`ARCHIVED` 且无清单」，下一次导出会把同一份轨迹重新认证并收敛；反过来（先翻状态）会留下「`ACTIVE` 却带着
一份描述旧轨迹的清单」，而导出的候选谓词是 `NOT EXISTS (清单)`——**它永远不会再被认证**，清理作业则每轮都
拒绝它。清单是**状态行**（候选谓词读它），冷行是**证据**：搬回不动冷表一个字节。**那份被留下的、已经没有清单的
冷副本由 7.13 退役回收**——搬回自己不碰它，是刻意的分工。

**D51 同时给两个冷热作业补上了会话锁。** 7.11 的「不加会话锁」有一个写下来的前提：候选都是 `ARCHIVED`，
写入路径拒绝写归档会话。**搬回是第一个能离开 `ARCHIVED` 的写者，前提当场失效**——而且摘要比对看不见它
（搬回不改轨迹的任何字节）。现在 `MedSessionPurgeService` 与 `MedSessionArchiveExportService` 的每个候选
都在会话锁内处理，并在锁内 `selectById` 重读状态：非 `ARCHIVED` 记 `skipped` 零改动，锁忙也记 `skipped`。

**排查**：

- 搬回返回 `40000` 且消息含 `retired` → 热行已被回收。用 `GET .../transcript` 确认能读到 `COLD`，然后
  按只读档案处理；想让它重新可写，只能新建一个会话。
- 搬回返回 `40000` 且消息含 `does not reproduce the certified digest` → 热轨迹与清单对不上（有人绕过写入
  路径动过行）。先查 `SELECT COUNT(*) FROM med_message_{crc32(session_id)%16} WHERE session_id='<id>'`，
  再跑一遍 D49 的读路径看它现在报哪一份，**不要**手工改行。
- 搬回返回 `40900` → 会话正忙或状态在迁移途中被并发改动，重试即可。
- 搬回后该会话的导出清单**不存在**是预期状态：它会在下次归档 + 导出时被重新认证，且认证会覆盖搬回之后
  新增的轮次。
- 跨组件契约：`MedSessionRestoreServiceTest`（三道检查各一条用例、**清单先删后翻的 `InOrder` 断言**）、
  `SessionControllerTest`、`MedSessionPurgeServiceTest` / `MedSessionArchiveExportServiceTest`（锁被占
  零改动、候选已被搬回零删除、状态重读在锁内），以及 `MedStorageAndLockIntegrationTest` 的收敛闭环
  「归档 → 导出 → 搬回 → 追加新轮次 → 再归档 → 重新导出 → 可清理」。

---

### 7.13 孤儿冷副本回收（D52）

D51 搬回一个会话时会撤销它的导出清单，但 `med_message_archive` 里的字节一行不动——于是冷表里留下了一份
**没有清单的孤儿快照**。它既不是**证据**（没有清单在认证它），也不是**状态**（没有谓词读它）：7.10 的读路径
只认清单，7.11 的清理只删 `med_message`。它只是占着空间的一份重复副本。D52 负责退役它，作业与调度器是
`MedSessionReclaimService` 与 `MedSessionReclaimScheduler`（`med.session.reclaim.enabled=false` 时二者
都不存在，定时器不碰数据库）。

**它回收什么**（三条候选谓词写进同一条 SQL，缺一即不是候选）：

1. `med_session.status < ARCHIVED`——会话**已经离开** `ARCHIVED`（搬回把它翻回了 `ACTIVE`）。仍为
   `ARCHIVED` 的会话正在被导出作业处理，一律不碰。用严格小于而不是 `<>`，是因为 `ARCHIVED` 恰好是枚举里
   最大的状态码，于是这条谓词在 `idx_med_session_retention (status, updated_at)` 上仍是一个**单区间**；
   这个等价关系由 `SessionReclaimMapperShardingTest#archivedIsTheHighestStatusCode` 钉住，改枚举顺序会变红。
2. `NOT EXISTS (SELECT 1 FROM med_session_archive WHERE session_id = s.session_id)`——**有清单的冷行是
   证据，一行都不能动**。这是本迭代的第一条硬约束。
3. `EXISTS (SELECT 1 FROM med_message_archive WHERE session_id = s.session_id)`——没有冷行的会话根本
   不需要回收。这条谓词**同时就是完成信号**：回收只删冷行，删完候选自然消失，所以**不需要退役标记列**
   （与 7.11 的 `med_session.purged_at` 相反）。

**它要求什么**（两道检查，缺一即 `refused`）：

1. **锁内重读**：候选是在锁外选出来的，而搬回（7.12）可以在两次读之间把会话送回 `ARCHIVED`。所以在会话锁内
   `selectById` 重读，状态不是「已离开 `ARCHIVED`」就记 `skipped` 零改动——否则会删掉导出作业正在认证的副本。
2. **包含关系**：冷副本的每个 `message_id` 都必须仍能在**活轨迹**里找到。孤儿没有清单，所以没有摘要可比，
   7.11 的「摘要复现」在这里无从下手；包含关系问的是唯一有意义的问题——**这份副本有没有可能承载活轨迹
   没有的东西**。有，即拒绝，并且**删 0 行**。

**顺序不可交换**：先在会话锁内证明包含关系，再删冷行（`SessionArchiveMapper#deleteColdCopy`，只按
`session_id` 删 `med_message_archive`）。只删冷副本，**热表、`med_session`、清单一个字节都不动**。

**它刻意不做什么**：**不加冷静期**。7.11 的 `min-archived-age` 是「活副本还能不能重建」的窗口，而 D52 删掉的
这份副本不承载任何能力——删了它，7.10 的读路径照样按清单作答（有清单的冷行根本不在候选里），被搬回的会话
照样可写。窗口只会推迟空间的释放，同时假装在保护什么。因此 `med.session.reclaim.*` 里**没有**
`min-archived-age` 这一族键，这是**契约而不是遗漏**（`MedSessionReclaimConfigTest#thereIsNoCoolingOffWindow`
钉住它，键缺失与「显式配置成 0」都判红）。

**互斥**：集群互斥锁是 `med:lock:session:reclaim`（与 `med:lock:session:purge` /
`med:lock:session:archive:export` 同族但**不同键**，不可配置）；每个候选还在
`med:lock:chat:{tenant}:{dept}:{session}` 会话锁内处理，与追加、归档、清理、搬回同一把锁。锁忙记
`skipped`（不是失败），Redis 不可达**向上抛**。

**告警**：`session-reclaim-completed`（INFO，**只在真删了东西且非 dry-run 时**发）、
`session-reclaim-refused`（WARNING，**在完成通知之前独立发出**）、`session-reclaim-failed`（ERROR）。
「作业停了」与「没有孤儿可回收」外观完全一样，所以拒绝更不能静默。

**排查**：

- 报告里 `refused > 0` → 消息里有具体原因。最常见的是「冷副本里有活轨迹找不到的 `message_id`」，说明有人
  绕过写入路径动过热表。先跑 7.10 的读路径确认还能读出什么，**不要**手工删冷行。
- 报告里 `candidates = 0` 且 `reclaimed = 0` → 没有孤儿，正常。孤儿只在「导出过、又被搬回」之后出现。
- 期望回收却一直 `skipped` → 检查该会话是不是又被归档了：`status = ARCHIVED` 时导出作业会重新认证它，
  此时它是证据，D52 不动它。
- 报告里出现 `skipped-lock-held` → 互斥生效，不是故障。**不要**为了让它跑起来去调小
  `MED_SESSION_RECLAIM_LOCK_WAIT`。
- 单实例调试：先 `MED_SESSION_RECLAIM_ENABLED=true` 且保持 `MED_SESSION_RECLAIM_DRY_RUN=true`，看一轮报告里的
  `candidates` / `refused` 是否合理，再关掉 dry-run。

**跨组件契约**：`SessionReclaimReportTest`、`MedSessionReclaimPropertiesTest`、
`MedSessionReclaimServiceTest`（三条候选谓词各有用例、锁内状态重读的 `InOrder`、包含关系失败即 `refused`
且零删除、dry-run 只验不删、锁忙零改动、单候选失败不中断整轮）、`MedSessionReclaimSchedulerTest`、
`MedSessionReclaimConfigTest`（开关真的增删 Bean、`@Scheduled` 占位符键在 `application.yml` 里真实存在、
**没有冷静期键**）、`SessionReclaimMapperShardingTest`（真实 DDL 下候选谓词逐条生效、删除只命中冷表），
以及 `MedStorageAndLockIntegrationTest` 在真实 MySQL + Redis 上验证
「导出 + 搬回制造孤儿 → 回收它 → 活轨迹一行不少、状态与清单不动」。

---

### 7.14 后台作业的运行账本与停摆检测（D53）

D47 / D48 / D50 / D52 的四份文档都写着同一句话——**「作业停了与医院没有可清理的会话外观完全一样」**——
但四个作业**没有任何东西能回答「它上一次运行是什么时候」**：告警只在**失败**时发（`-failed`），
或在**真的做了事**时发（`-completed` INFO，安静的一夜什么都不发）。于是「作业健康且无事可做」与
「作业在三个月前就死了」的可观测面完全相同：零告警、零指标、零日志。

更根本的一条：**依赖作业运行才能产生的告警，永远无法发现作业没有运行**。四个 `-failed` 规则全部由作业
自己发出，所以它们对「作业整个停了」这一类故障结构性失明。D53 补上这条只能由**数据的缺席**来表达的信号。

#### 应用发布什么

| 指标 | 类型 | 含义 |
|---|---|---|
| `med_qa_job_last_run_epoch_seconds{component}` | gauge | 最近一次**尝试**的墙钟秒。**每一种结局都会刷新它**，包括抢不到锁而让位的那些 |
| `med_qa_job_last_success_epoch_seconds{component}` | gauge | 最近一次**真正跑完 passes** 的墙钟秒。只有 `COMPLETED` 刷新它 |
| `med_qa_job_expected_interval_seconds{component}` | gauge | 该作业被装配时的 `check-interval`（秒）。**规则用它当阈值**，所以改配置不需要改规则 |
| `med_qa_job_runs_total{component,outcome}` | counter | 按结局计数的运行次数，`outcome` ∈ `COMPLETED` / `SKIPPED_LOCK_HELD` / `FAILED` |

`component` 的取值就是四个作业的告警 component：`session-retention`、`session-archive`、
`session-purge`、`session-reclaim`。

> **为什么标签叫 `component` 而不是 `job`**：Prometheus 把 `job` 与 `instance` 保留给抓取目标，
> 在默认的 `honor_labels: false` 下，被采集指标里同名的标签会被改名为 `exported_job`，而目标自己的
> `job="med-qa"` 覆盖它。于是一条写着 `job=~"session-.*"` 的规则会**解析成功、采集成功、永远不匹配任何
> 数据**——正是 D53 要消灭的那类静默失效。复用 `component` 还让选择器与
> `med_qa_alert_total{component=...}` 用同一套子系统命名，一块面板同时覆盖两者。
> `AlertingStackConfigTest` 把两半都钉住：标签名，以及抓取配置**没有**打开 `honor_labels`。

#### 两条规则，问的是两个不同的问题

| 规则 | 级别 | 表达式（节选） | 回答的问题 |
|---|---|---|---|
| `MedQaBackgroundJobStalled` | warning | `time() - med_qa_job_last_run_epoch_seconds{component=~"session-.*"} > 2.5 * med_qa_job_expected_interval_seconds{...} + 300`，`for: 15m` | **这个 Pod 的调度线程是不是停了**（按实例） |
| `MedQaBackgroundJobNotCompleting` | **critical** | `time() - max by (component)(med_qa_job_last_success_epoch_seconds{...}) > 2.5 * max by (component)(med_qa_job_expected_interval_seconds{...}) + 300`，`for: 15m` | **有没有任何副本真的在干活**（按集群） |

**为什么必须分开**：`last_run` 在 `SKIPPED_LOCK_HELD` 时也会刷新（抢不到锁是互斥在工作，不是实例停摆），
所以一个**被泄漏的 `med:lock:session:*` 键**会让某个副本永久占着互斥，其余副本每轮都记
`SKIPPED_LOCK_HELD`——`last_run` 看起来完全新鲜、四个作业却全部变成空转。只有
`last_success` 的**集群最大值**能发现它。`MedQaBackgroundJobNotCompleting` 因此**page**：它是唯一一条
由数据的缺席产生的信号，作业自己发出的任何告警都覆盖不了「作业什么都没发出」这种情况。

**阈值为什么是 2.5 倍加 300 秒**：`@Scheduled(fixedDelay)` 从上一轮**结束**开始计时，所以两次成功之间
的间隔约为 `interval + 运行耗时`；2.5 倍给慢轮次留了余量，`+300` 让「刚启动」不会误报。

#### 现场处置

| 现象 | 含义 | 处置 |
|---|---|---|
| `MedQaBackgroundJobStalled` 只对某一个 `instance` 触发 | 那个 Pod 的调度线程没了（Bean 被摘掉、定时器没起来） | 重启该 Pod；若重启后仍不触发，检查该作业的 `enabled` 是否被改成了 `false`——**关闭的作业不发布任何序列，规则也不会触发**，两者外观不同 |
| `MedQaBackgroundJobNotCompleting` 触发，`runs_total{outcome="FAILED"}` 在涨 | 每轮都抛异常 | 先看 `-failed` 告警与日志，多半是 MySQL / Redis 不可达 |
| `MedQaBackgroundJobNotCompleting` 触发，`runs_total{outcome="skipped_lock_held"}` 在涨、`completed` 为 0 | 互斥被某个副本永久占着 | `redis-cli --scan --pattern 'med:lock:session:*'` 找出残留键；确认持有者进程是否已被强杀。确认无人在跑后可手工 `DEL` 该键，作业下一轮自动恢复 |
| 两条都不触发、但业务上感觉作业没跑 | 大概率是**作业被关掉了** | `med.session.*.enabled=false` 时账本不发布序列，规则按设计不告警。用 `/actuator/info` 或配置核对，**不要**去改规则 |

#### 这套机制看不见什么（边界，不是缺陷）

- **重启频率高于阈值的 Pod 会重置自己的时钟**：两个时间戳在 `register` 那一刻起算（不是从 0 起算，
  否则每次重启后的整个 `initial-delay` 都会误报），所以一个不断崩溃重启的 Pod 可以一直让时间戳保持新鲜。
  这是**进程内心跳**的固有边界，由进程层面覆盖：崩溃重启的 Pod 会让 `up` 抖动，
  `MedQaTargetDown` 抓得到。要一条**抗崩溃循环**的死信开关，只能把心跳落到进程之外（外部探针或持久化）。
- **两个告警监控器（`MedStorageAlertMonitor` / `MedVectorIndexAlertMonitor`）不在范围内**：它们的存活
  问题是个先有鸡还是先有蛋的问题——告警链无法报告自己的生产者已死——只能由 `MedQaTargetDown` 在进程层面
  覆盖。这是**刻意的范围边界**，不是遗漏。
- **账本自身没有任何开关**：它是「作业没跑」的唯一证据来源，能把它关掉等于一次性关掉四个作业的可观测性。
  作业被关闭时它自然不发布序列，这就够了。
- **不发布业务计数**（`candidates` / `archived` / `purged` 这类）：D53 只回答「作业还活着吗」，
  把「作业干得怎么样」混进来会让两条规则的判据重新变得含混。业务计数仍在各自的报告里。

#### 跨组件契约

`MedJobRunLedgerTest`（注册幂等且**不重置**时间戳、两个时间戳在注册时都等于当前时刻——「重启后不误报」
的可执行版本、`SKIPPED_LOCK_HELD` 只刷新 `last_run`、`FAILED` 只刷新 `last_run`、三种结局各自计数、
未注册的作业 `record` 直接拒绝、两个作业的标签互不串扰）、`MedJobRunTest`、
`MedJobObservabilityConfigTest`（**四个作业全关时账本仍在**）、四个 `*SchedulerTest`（三种结局分别记进
账本，且抛异常时账本**先于**告警——用 `InOrder` 断言，因为告警链自己挂掉时不能再把「作业跑过」这条唯一
证据一起丢掉）、四个 `*ConfigTest`（**账本发布的 `expected-interval` 与 `@Scheduled` 占位符读到的是同一个
`check-interval`**，占位符的 `:1h` 字面回退一旦生效就会在这里变红）、`AlertingStackConfigTest`
（规则里出现的每个 `med_qa_job_*` 名字都必须属于 `MedJobRunLedger` 的常量、三个 gauge 必须都被引用、
选择器必须匹配四个作业的 `COMPONENT` 且**不匹配**两个监控器的 component、抓取配置不得打开
`honor_labels`）。

按项目惯例，本轮的新守卫**先被亲眼看过它变红**：把账本的 `record` 改成只在 `COMPLETED` 时刷新
`last_run` → `MedJobRunLedgerTest` 的「被互斥挡下仍算一次尝试」用例变红；把 `register` 改成每次都重置
时间戳 → 「幂等不重置」用例变红；把 `MetricsMedAlertSink` 的错误承诺还原 → 文档守卫变红。

---

### 7.15 冷库规模与年龄上报（D54）

D48 把归档轨迹写进冷表 `med_message_archive`，D50 清掉已认证的热行，D51 的搬回留下孤儿冷副本，D52 回收它。
这条链路自此完整，但**没有任何指标说冷表现在有多大、最老的数据有多旧**——「回收在正常工作」与「回收
追不上产生速度」的外观仍然一样：安静一晚、零告警、零指标。D54 把这两件事做成可观测。

#### 应用发布什么

| 指标 | 类型 | 含义 |
|---|---|---|
| `med_qa_cold_store_rows` | gauge | `med_message_archive` 当前的行数（`COUNT(*)`）——冷库有多大 |
| `med_qa_cold_store_oldest_row_age_seconds` | gauge | 冷库最老一行的年龄（秒），由 `MIN(created_at)` 推出；空表为 `0` |
| `med_qa_cold_store_reclaim_backlog` | gauge | 上一次**被允许删除**的回收运行结束后仍存在的孤儿冷副本数 |

- 三个 gauge 都由 `MedColdStoreMetrics`（`com.med.qa.job`）发布，**第一次测量时才注册**（懒注册）。
  作业全部关闭的部署**一条 `med_qa_cold_store_*` 序列都不发布**——预先导出一个 `0` 会被读成「冷库是
  空的」，而这是一个没有任何人验证过的断言。
- **行数与年龄是数据的事实，不是动作的事实**：D48 导出与 D52 回收在每一轮**持有集群互斥锁**的运行之后
  都会刷新它们，dry-run 也包括在内——只做测量的部署同样报告冷库有多大。让位给别的副本的运行什么都不
  发：握着锁的那个副本在测。
- **积压量只由被允许删除的运行发布**（`report.remaining()`，即回收报告自己的积压量，不新写扫描）：
  dry-run 的积压量按定义不可能下降，把它发出去会让一条问「作业在跑、积压为什么不降」的规则永久误报
  那些**只测量**的部署。dry-run 回到 `true` 的部署会保留上一次删除运行的数值（Micrometer 持续导出
  gauge 的最后一个值），这是**写明的取舍**而非疏漏。
- 三个读取都是聚合（`COUNT(*)` / `MIN(created_at)`）：**只读元数据**，不解码任何 Protobuf 正文，
  也不新增任何删除能力。

#### 一条规则

| 规则 | 级别 | 表达式 | 回答的问题 |
|---|---|---|---|
| `MedQaColdStoreBacklogGrowing` | warning | `min_over_time(med_qa_cold_store_reclaim_backlog[24h]) > 0`，`for: 6h` | 积压量**一整天从没降到过 0**：回收在跑，却永远追不上 |

**为什么是 `min_over_time` 而不是导数**：健康的排空在每个采样点都读 `0`，而对一个阶梯状、稀疏采样的
gauge 求导只会新增故障模式（窗口里要凑够两个点、对采样间距敏感），并不新增一个问题——
`min_over_time[24h] > 0` 恰好就是「积压量一直在、从没清空」；采样稀疏时它退化成「没有数据」而不是
一个错误结论。

#### 现场处置

| 现象 | 含义 | 处置 |
|---|---|---|
| `MedQaColdStoreBacklogGrowing` 触发 | 回收跑着但从不排空：restore 产生孤儿的速度快于回收退役它们的速度，或有副本一直被拒 | 提高 `med.session.reclaim.max-batches` / `batch-size`；同时看 `MedQaSessionReclaimRefused`——**被拒的副本会永远留在候选集里** |
| 规则不触发、但冷库在变大 | 看 `med_qa_cold_store_rows` 的斜率：正常的导出增长是数据在增加，不是故障 | 属于容量规划（§11），不是回收链路问题 |
| 三条序列都不存在 | 作业被关掉，或从未跑完过一轮 | 核对 `med.session.archive.enabled` / `med.session.reclaim.enabled`；**故意关闭是运维决策，规则按设计不告警** |

#### 这套机制看不见什么（边界，不是缺陷）

- **只有回收允许删除时才产生积压序列**：dry-run 部署不会被这条规则 page——它的积压不可能排空。
  行数与年龄仍然照常发布，因为它们是数据的事实。
- **冷表最老一行不等于最老的孤儿**：`med_message_archive` 里也躺着有清单认证的证据行，
  `MIN(created_at)` 覆盖整张表。它回答的是「冷库里最老的数据有多旧」；在孤儿占多数的表上，它就是
  「最老的孤儿待了多久」的元数据答案，但它不是孤儿的精确年龄。
- **没有一个开关**：能关掉这三个 gauge 的开关等于关掉冷库唯一的规模信号。懒注册已经让「故意关闭的
  部署」什么都不发布，这就够了。
- **D54 不改任何生命周期语义**：只读元数据，不新增删除能力。

#### 跨组件契约

`MedColdStoreMetricsTest`（懒注册、空表年龄为 0 而非纪元、未来时间戳钳到 0、负数行数归一化、测量失败
保留旧值且不抛、积压与规模两组 gauge 独立注册、负积压拒绝）、`MedColdStoreMetricsConfigTest`（四个作业
全关时发布器仍在、空闲上下文一条序列都没有、`application.yml` 里没有 `med.cold-store` 开关）、两个
`*SchedulerTest`（dry-run 只刷新规模、删除运行同时发布积压、让位的运行什么都不发、失败什么都不发、
未测量的积压 `-1` 不发布）、`SessionReclaimMapperShardingTest`（两个聚合在真实 H2 + ShardingSphere 上
跑通：`COUNT(*)` 只数冷表、`COALESCE(MIN(created_at), 0)` 取最小值且空表为 0）、`AlertingStackConfigTest`
（规则引用的每个 `med_qa_cold_store_*` 名字都必须属于 `MedColdStoreMetrics` 的常量、表达式里不得出现
`deriv(`）。

按项目惯例，本轮的新守卫**先被亲眼看过它变红**：删掉 DEPLOYMENT.md 的 §7.15 标题与两份 README 的 D54
小节标题 → 三个文档守卫各红一条；把 `MedSessionReclaimScheduler` 的 dry-run 边界去掉 →
"a dry run publishes the size but no backlog" 变红。两处还原后重跑全绿。

---

## 8. 日志与故障排查

```bash
docker compose logs -f app            # 应用日志
docker compose logs -f mysql redis-stack
```

| 现象 | 原因 | 处理 |
|---|---|---|
| `app` 反复重启，`/actuator/health` 不通 | MySQL / Redis 未就绪或凭据错误 | 查 `MED_MYSQL_*` / `REDIS_*`，确认 `docker compose ps` 两个依赖 healthy |
| 启动报向量索引创建失败 | Redis 不是 Stack（缺 RediSearch） | 换成 `redis/redis-stack` 镜像或加载模块 |
| 启动报维度不匹配 | `MED_EMBEDDING_DIMENSIONS` 与已有索引宽度不一致 | 对齐维度后重建索引（删索引 → 重新入库） |
| 全部请求 401 | `MED_SECURITY_ENABLED=true` 但未配置 API Key | 关闭开关（仅本地）或注入密钥映射 |
| 请求 403 | 患者跨会话 / 跨科室访问 | 预期行为，检查 `patient_id` / `dept_id` 归属 |
| 返回 `40900` | 并发写入同一会话，Redisson 锁未获取 | 客户端重试；或调大 `MED_LOCK_WAIT_TIME` |
| 返回 `42900` | 触发限流 | 调整 `MED_RATE_LIMIT_DEFAULT_RATE` / `DURATION` |
| 返回 `50201` | LLM / Embedding 网关不可达 | 检查 `OPENAI_BASE_URL` / `OPENAI_API_KEY` 与网络 |
| 返回 `50301` | MySQL / Redis 故障 | 检查中间件连通性与连接池 |
| SSE 连接中途断开 | 超过 `MED_CHAT_STREAM_TIMEOUT` 或网络中断 | 调大超时；客户端实现重连 |

---

## 9. 备份与回滚

**备份**

```bash
# MySQL 逻辑备份（含 16 张分表 + 会话 + 审计 + 冷归档表）
docker exec med-qa-mysql mysqldump -uroot -p"$MED_MYSQL_ROOT_PASSWORD" \
  --single-transaction --routines --triggers med_qa > med_qa_$(date +%F).sql

# Redis 持久化（已在 compose 中开启 AOF：--appendonly yes --save 60 1000）
docker compose exec redis-stack redis-cli BGSAVE
```

**回滚**

```bash
# 1) 回退镜像版本
docker compose down
# 修改 .env / compose 中 app 的 image 标签为上一个版本
docker compose up -d

# 2) 若迁移不兼容，先恢复数据
docker compose exec -T mysql mysql -uroot -p"$MED_MYSQL_ROOT_PASSWORD" med_qa < med_qa_<date>.sql
```

> Flyway 迁移只增不改；回滚到旧版本前请确认旧版本能兼容当前 schema。

---

## 10. 安全加固清单

- [ ] `MED_SECURITY_ENABLED=true` 并注入真实 API Key 映射（绝不写进仓库或镜像层）
- [ ] `MED_SECURITY_DEPT_SCOPE_ENABLED=true`（科室越权 403 由拦截器直接写出，不经异常处理器）
- [ ] `MED_RATE_LIMIT_ENABLED=true`，按科室/患者维度设置合理阈值
- [ ] `MED_AUDIT_ENABLED=true`，审计表保留周期符合院内合规要求
- [ ] 修改 Compose 默认口令（`MED_MYSQL_ROOT_PASSWORD` / `MED_MYSQL_PASSWORD`）
- [ ] 生产不映射 `8001`（RedisInsight）与 `3306`（MySQL）到公网
- [ ] 镜像以非 root `medqa` 用户运行（Dockerfile 已固定）
- [ ] 敏感字段经 `@Desensitize` 输出；审计与日志只记录标识符，不记录问诊文本
- [ ] LLM / Embedding 出网走院内网关，密钥由密钥管理系统注入
- [ ] 客户端不要依赖请求体里的身份字段：`/api/chat/stream` 的 tenant/dept/patientId 只是**一致性声明**，与 API Key 不一致即 403（D41，见 10.1）
- [ ] 运维脚本不要用「按 documentId 删除」的旧姿势：`/api/rag/documents/delete` 已**只支持按隔离 scope 删除**，且部门级删除必须显式带 `confirmDepartmentWide=true`（D42，见 10.2）
- [ ] **不开启 H2 Web Console**（`spring.h2.console.*` 不得出现在任何配置文件中）；H2 只是 `runtime` scope 的测试替身，收窄为 `test` 会让 ShardingSphere 启动失败（D46，见第 1 节）
- [ ] **MySQL 库默认字符集为 `utf8mb4`**，并在上线前用 `SHOW CREATE DATABASE med_qa` 核对；连接串 `characterEncoding` 必须写 Java 字符集名 `UTF-8`（D46，见第 1 节）

### 10.1 流式问诊的身份来源与拒绝语义（D41）

**排障第一原则：`/api/chat/stream` 的身份只来自 API Key。** 请求体里的 `tenant` / `dept` /
`patientId` 是可选的**一致性声明**，`RequestIdentityGuard` 只做校验、不提供授权。因此：

| 现场现象 | 真实原因 | 处置 |
|---|---|---|
| 同一份 JSON 昨天能用、今天 403 `tenant mismatch` | 客户端换过 Key，或 Key 的 tenant 与请求体写的不一致 | 对比 `MedPrincipal`（Key 映射）与请求体；让客户端**删掉**请求体身份字段即可彻底避免 |
| 403 `department mismatch` | 请求体的 dept 不是该 Key 所属科室（跨科室） | 同上；注意患者与医生 Key 都绑定单一科室 |
| 403 `patient mismatch` | 请求体 patientId 不是该患者自己的 | 患者端不要传 patientId，身份由 Key 决定 |
| 403 `authentication required` | 没带 `X-API-Key`，或 `MED_SECURITY_REQUIRE_AUTH` 下缺失 | 见 5.3；注意 `MED_SECURITY_ENABLED=false` 会让 principal 为空，**此时流式端点会一律 403**，只适用于本地开发 |
| 404 | 会话在该 tenant/dept 下不存在（跨科室的会话一律按「不存在」回答，不确认它存在别处） | 核对 `med_session` 与 sessionId |
| 400 | `session` / `message` 缺失；或会话已 CLOSED / ARCHIVED（`requireWritableSession`） | 归档会话需要新开一个 session |
| 503 + `50201` | 没有可用模型（`spring.ai.model.chat` 未启用或没有 API Key） | 见 5.2 |
| 请求体只带 session/message 也成功 | **这是设计**：身份完全来自 principal | 无需修改客户端 |

> **D41 的真实收获**：写上面这张表时，跨组件契约测试当场查出「模型未配置」的现场表现与文档不符——
> Spring AI 的 `ChatClient.Builder` bean 带一个**必需**的 `ChatModel` 参数，`ObjectProvider.getIfAvailable()`
> **不会吞掉实例化失败**，于是未配模型的部署拿到的是 bean 创建栈 + `500`，而不是 `503 + 50201`。
> 代码已修（把 `BeansException` 与「返回 null」折叠成同一个 `LLM_SERVICE_ERROR`）。
> 提示：本机没有模型时的启动/探活**不会**报错——builder 是 prototype bean，只有第一个流式请求才触发，
> 所以这类问题只会在上线后第一次问诊时暴露。

拒绝一律发生在 SSE 打开之前，返回对应 HTTP 状态 + 单个 `error` 事件（`<code> <message>`），
不会留下半开连接。这些错误**不经过全局异常处理器**——`text/event-stream` 渲染不了它的 JSON 信封，
放过去只会变成语义不明的协商错误。

排查顺序（与代码一致）：身份解析 → `PatientAccessGuard.assertScope` → `requireWritableSession` →
模型可用性。**授权先于能力**，任何一步失败都不会产生 LLM 调用，所以日志里看不到模型侧痕迹时，
问题一定在前面三步。跨组件契约由 `StreamingIdentityContractTest` 守住（真实 API Key 链路上验证
「声明他人身份被拒」与「声明缺省时到达会话层的是 principal 的坐标」）。

---

### 10.2 RAG 管理端的授权与删除语义（D42）

**排障第一原则：`/api/rag/**` 的 tenant / dept / patient 也只来自 API Key。** 请求体里的
`tenantId` / `deptId` / `patientId` 是可选的**一致性声明**，由同一个 `RequestIdentityGuard` 校验；
**判定以业务码为准**——拦截器写出的拒绝是真实 HTTP `403`，处理器内部抛出的 `BizException` 按项目
统一约定返回 HTTP `200` + 业务码 `40300` / `40000`。

| 现场现象 | 真实原因 | 处置 |
|---|---|---|
| HTTP 200 但 `code=40300`、`message=department mismatch` | 请求体写的 `deptId` 不是该 Key 所属科室 | 对比 Key 映射与请求体；让调用方**删掉**请求体里的身份字段即可彻底避免 |
| HTTP 200 但 `code=40300`、`message=tenant mismatch` | 请求体写的 `tenantId` 不是该 Key 的租户 | 同上 |
| HTTP 403（拦截器直接写出，非 `ApiResult` 信封） | 用的是 PATIENT Key，或请求**信封**里带了 `deptId` 参数/头 | `/api/rag/**` 仅 STAFF；身份字段请放 body，别放 query/header |
| HTTP 200 但 `code=40000`、`message=deleting the whole department scope ...` | 删除请求没写 `patientId` 又没确认 | 补 `"confirmDepartmentWide": true`，或改成按患者删除 |
| 传了 `ids` 却什么都没删 | **按 id 删除已下线**（D42）：`ids` 不再是已知字段，Jackson 忽略它，请求退化成「未确认的部门级删除」而被拒 | 改用 scope 删除；需要精确删除单个文档时，先删该患者/科室 scope 再重新入库 |
| 删除后该科室问诊「指南不见了」 | 部门级删除会连**共享指南**一起删掉（这是设计） | 重新入库共享指南，或改用按患者删除 |
| `MED_SECURITY_ENABLED=false` 下所有 `/api/rag/**` 都拒绝 | principal 为空，守卫 fail closed | 本地开发才可关闭安全；见 5.3 |

**为什么按 id 删除被移除**（这是 D42 的核心）：向量库用文档 id 作 Redis key、隔离标签存在 JSON
值里、RediSearch **不索引 key**，因此「这批 id **且** 属于我的 scope」无法表达成一个过滤表达式；
而无 scope 谓词的 `VectorStore#delete(List<String>)` 永远做不成 scope 安全——改动前任何 STAFF Key
只要猜到一个 documentId，就能物理删除别的科室的向量。所以 `MedDocumentService` 里这个原语被**删除**
而不是加守卫，并有反射守护测试（`MedDocumentServiceDeleteTest$NoIdBasedDeletion`）钉住「它不存在」。
将来若真需要精确删除，正确做法是把文档 id 作为一个 TAG 元数据字段**显式纳入索引 schema**，
而不是绕过隔离模型。

跨组件契约由 `RagAdminAuthorizationContractTest` 守住（真实 API Key → 拦截器 → 控制器 → 守卫 → 服务）。
按项目惯例，这条守卫先被亲眼看过变红：把 scope 改回「body 优先」后，两个测试类共 12 个用例失败。

---

## 11. 容量与调优建议

| 维度 | 建议 |
|---|---|
| JVM | 容器内已设 `MaxRAMPercentage=75.0` + `ExitOnOutOfMemoryError`，按 cgroup 限制自动伸缩 |
| MySQL 连接池 | `sharding/med-sharding.yaml`：`minimumIdle=4` / `maximumPoolSize=32`，按并发问诊量调整 |
| Redis 缓存窗口 | `MED_CACHE_MAX_MESSAGES` 控制单会话缓存条数；读 miss 会自动回源 MySQL 并回填。**置 `0` 会同时解除缓存与回源截断**，等于把整段历史交给模型 prompt（D46，见 7.6） |
| 会话锁 | `MED_LOCK_LEASE_TIME=0`（看门狗）适配慢 LLM 往返；崩溃节点最迟 `MED_LOCK_WATCHDOG_TIMEOUT` 释放 |
| 向量检索 | 语量小可切 `MED_RAG_VECTOR_ALGORITHM=FLAT` 做精确检索；量大保持 `HNSW` |
| 限流 | 默认 10 次/秒/调用方；可按接口用 `@RateLimit(rate=..., durationSeconds=...)` 细调 |
| 水平扩容 | 应用无状态（会话与锁在 Redis、数据在 MySQL），可直接多副本 + 负载均衡 |

---

**相关文档**：[README（总览与快速开始）](../README.md) ｜ [ROADMAP（迭代路线）](../ROADMAP.md)
