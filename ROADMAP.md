# springai-med-qa 开发路线图

> 医院生产级 AI 问诊后端服务（Java / Spring Boot 3 / Spring AI）
> 仓库路径：`D:\javaproject\springai-med-qa` ｜ 独立仓库，独立推送 GitHub
> 技术路线：**全面采用成熟商业化/主流开源组件，不自研底层组件**，专注业务集成与工程质量

---

## 一、项目简介

**定位**：医院生产级 AI 问诊后端服务，基于 Spring AI 官方组件体系构建：官方 `ChatMemory` + 自定义仓储对接统一医疗会话存储规范（字段与序列化协议与 med-langchain-memory 对齐，但代码零依赖、完全独立），RAG 采用 Spring AI 官方 `VectorStore`（Redis Stack 向量检索），专注业务层、权限控制、流式 LLM 问答、数据脱敏与容器化部署。

**核心能力**：
- 会话记忆：Spring AI 官方 `ChatMemory` 体系 + 自定义 `ChatMemoryRepository` 仓储实现，MySQL 分表（ShardingSphere-JDBC）+ Redis 缓存双层存储，Protobuf 序列化，字段规范与统一存储协议完全对齐
- RAG 检索：Spring AI 官方 `RedisVectorStore` + `QuestionAnswerAdvisor`，元数据标签过滤检索（科室/患者ID 标签，**不解析文本内容**），Embedding 由外部模型 API 生成
- 流式问诊接口（SSE）、患者会话权限校验、Redisson 分布式锁与限流、医疗操作日志审计（AOP）、隐私字段脱敏注解（Hutool 脱敏工具）
- Docker 多阶段打包、GitHub Actions 自动构建镜像推送、Swagger/OpenAPI 文档

**组件选型原则（成熟组件优先，零自研底层）**：

| 能力 | 采用的成熟组件 | 不再自研的内容 |
|---|---|---|
| 向量存储与检索 | Spring AI `RedisVectorStore`（Redis Stack RediSearch） | ~~余弦/L2 计算、Top-K 堆、LRU 淘汰、快照持久化~~ |
| MySQL 分表 | ShardingSphere-JDBC（配置化分片，crc32 分片算法插件类） | ~~手写分表路由~~ |
| 分布式锁 | Redisson `RLock`（自带看门狗续期） | ~~手写 SETNX + Lua~~ |
| 接口限流 | Redisson `RRateLimiter` | ~~手写令牌桶~~ |
| 字段脱敏 | Hutool `DesensitizedUtil` + Jackson 注解封装 | ~~手写掩码策略~~ |
| RAG 编排 | Spring AI `QuestionAnswerAdvisor` + Filter Expression | ~~手写检索拼接管线~~ |
| Embedding | Spring AI `EmbeddingModel`（OpenAI 兼容 API） | ~~手写 WebClient 适配器~~ |

**明确不做**：无任何文本预处理客户端、无内容解析拦截器；不自研任何向量数学 / 存储引擎 / 锁 / 限流底层组件。

**技术栈**：Java 17 / Spring Boot 3 / Spring AI / MyBatis / ShardingSphere-JDBC / MySQL / Redis Stack / Redisson / Protobuf / Hutool / Spring Security / SpringDoc / Flyway / JUnit5+Mockito / Testcontainers / Docker / GitHub Actions

---

## 二、完整分层目录结构

```
springai-med-qa/
├── pom.xml
├── README.md
├── ROADMAP.md                      # 本文件
├── Dockerfile                      # 多阶段构建
├── docker-compose.yml              # mysql + redis-stack + app
├── .github/workflows/
│   ├── ci.yml                      # mvn verify + jacoco
│   └── docker-publish.yml          # 构建并推送镜像至 GHCR
├── src/main/proto/
│   └── med_session.proto           # 统一序列化协议（与存储规范同源副本）
├── src/main/java/com/med/qa/
│   ├── MedQaApplication.java
│   ├── config/                     # 配置层
│   │   ├── RedissonConfig.java     # Redisson 客户端
│   │   ├── VectorStoreConfig.java  # Spring AI RedisVectorStore
│   │   ├── SecurityConfig.java
│   │   ├── SpringAiConfig.java     # ChatClient / EmbeddingModel
│   │   └── OpenApiConfig.java
│   ├── common/                     # 通用层
│   │   ├── result/ApiResult.java   # 统一响应体
│   │   ├── exception/              # 全局异常处理
│   │   ├── ratelimit/              # @RateLimit 注解 + Redisson RRateLimiter 切面
│   │   └── util/
│   ├── domain/                     # 领域层
│   │   ├── entity/                 # ChatSessionDO / ChatMessageDO / AuditLogDO
│   │   ├── enums/                  # RoleType / SessionStatus
│   │   └── dto/                    # 请求/响应 DTO
│   ├── memory/                     # 会话记忆层（官方 ChatMemory + 自定义仓储）
│   │   ├── MedChatMemoryRepository.java  # 实现 Spring AI ChatMemoryRepository
│   │   ├── serde/ProtoMessageCodec.java  # DO ↔ Protobuf 编解码
│   │   ├── cache/RedisMessageCache.java  # Spring Data Redis 缓存读写
│   │   ├── sharding/Crc32ShardingAlgorithm.java  # ShardingSphere 分片算法插件
│   │   └── lock/SessionLockService.java  # Redisson RLock 封装
│   ├── rag/                        # RAG 层（Spring AI 官方组件装配）
│   │   ├── MedDocumentService.java # 文档 + 科室/患者标签入库 VectorStore
│   │   └── MedRagAdvisorFactory.java # QuestionAnswerAdvisor + 标签过滤表达式
│   ├── mapper/                     # MyBatis Mapper（走 ShardingSphere 数据源）
│   │   ├── ChatMessageMapper.java
│   │   ├── ChatSessionMapper.java
│   │   └── AuditLogMapper.java
│   ├── service/                    # 业务服务层
│   │   ├── ChatService.java        # 流式问诊编排
│   │   ├── SessionService.java
│   │   ├── RagService.java
│   │   └── AuditService.java
│   ├── security/                   # 权限层
│   │   ├── ApiKeyAuthFilter.java
│   │   ├── PatientAccessGuard.java # 患者-会话归属校验
│   │   └── annotation/RequireDept.java
│   ├── audit/                      # 审计层（AOP）
│   │   ├── AuditAspect.java
│   │   └── annotation/MedAudit.java
│   ├── privacy/                    # 脱敏层（Hutool 封装）
│   │   ├── annotation/Desensitize.java
│   │   ├── DesensitizeSerializer.java  # Jackson 序列化器，内部调 Hutool
│   │   └── MaskType.java           # 手机号/身份证/病历号类型枚举
│   └── controller/
│       ├── ChatController.java     # SSE 流式问诊
│       ├── SessionController.java
│       └── RagAdminController.java
├── src/main/resources/
│   ├── application.yml             # 含 shardingsphere 分片规则配置
│   ├── mapper/*.xml
│   └── db/migration/               # Flyway 建表脚本（含16张分表）
└── src/test/java/com/med/qa/       # 与 main 镜像的全量单测
    ├── memory/ rag/ service/ security/ privacy/
    └── integration/                # Testcontainers 集成测试
```

---

## 三、分阶段每日迭代任务（每个 30–60 分钟，单一功能，独立 commit）

### 阶段 0：工程基建（D1–D5）

| Day | 任务 | 实现要点 | Commit 信息 |
|---|---|---|---|
| D1 | 项目脚手架 | Spring Boot 3 + 分层包结构、application.yml 多环境、.gitignore、Maven Wrapper | `chore: bootstrap spring boot skeleton with layered packages` |
| D2 | 统一响应与异常 | `ApiResult<T>`、全局 `@RestControllerAdvice`、错误码枚举 + 单测 | `feat(common): add unified api result and global exception handler` |
| D3 | 领域实体 | `ChatSessionDO`/`ChatMessageDO` 字段严格对齐统一存储规范 | `feat(domain): add session and message entities aligned with storage spec` |
| D4 | Protobuf 集成 | 引入 protobuf-maven-plugin，编译 med_session.proto，生成类单测 | `feat(proto): integrate protobuf codegen for unified session schema` |
| D5 | CI 流水线 | GitHub Actions：mvn verify + JaCoCo 覆盖率报告上传 | `ci: add maven verify workflow with jacoco coverage` |

### 阶段 1：会话记忆层（D6–D12，官方 ChatMemory + 成熟组件）

| Day | 任务 | 实现要点 | Commit 信息 |
|---|---|---|---|
| D6 | ShardingSphere 分表 | 引入 shardingsphere-jdbc，YAML 配置 `med_message_{0..15}` 分片规则 + `Crc32ShardingAlgorithm` 分片算法插件类（仅实现接口，路由由框架完成）| `feat(memory): configure shardingsphere jdbc with crc32 table sharding` |
| D7 | Proto 编解码 | `ProtoMessageCodec`：DO↔protobuf 双向转换 + round-trip 单测 | `feat(memory): add protobuf codec aligned with unified schema` |
| D8 | MySQL 存取 | Flyway 16 张分表 DDL + MyBatis Mapper CRUD（走 ShardingSphere 数据源，透明分表） | `feat(memory): add flyway sharded ddl and mybatis message mapper` |
| D9 | Redis 缓存 | `RedisMessageCache`：Spring Data Redis，键规范 `med:chat:{tenant}:{dept}:{session}`、TTL | `feat(memory): add redis message cache with spec-compliant key schema` |
| D10 | 双层读写 | `MedChatMemoryRepository` 读走缓存、写双写（cache-aside），MySQL 兜底回填 | `feat(memory): implement chat memory repository with cache-aside strategy` |
| D11 | Redisson 分布式锁 | 引入 redisson-spring-boot-starter，`SessionLockService` 封装 `RLock`（看门狗自动续期）+ 并发单测 | `feat(memory): add redisson-based distributed session lock` |
| D12 | ChatMemory 接入 | 装配 Spring AI `MessageWindowChatMemory` + 自定义仓储，接入 ChatClient 会话链路 | `feat(memory): wire spring ai chat memory over custom repository` |

### 阶段 2：RAG 检索层（D13–D18，Spring AI 官方 VectorStore）

| Day | 任务 | 实现要点 | Commit 信息 |
|---|---|---|---|
| D13 | VectorStore 配置 | 引入 spring-ai redis vector store starter，docker-compose 加 redis-stack，`VectorStoreConfig` 装配 Bean（索引名/前缀/距离度量配置化） | `feat(rag): configure spring ai redis vector store` |
| D14 | EmbeddingModel 配置 | Spring AI OpenAI 兼容 `EmbeddingModel` 配置（baseUrl/apiKey/超时重试均走官方配置项）+ mock 单测 | `feat(rag): configure openai-compatible embedding model` |
| D15 | 文档入库 | `MedDocumentService`：构造 `Document` + 科室/患者ID 元数据标签，批量写入 VectorStore | `feat(rag): add document ingestion with dept and patient metadata tags` |
| D16 | 标签过滤检索 | 用官方 `FilterExpressionBuilder` 实现科室/患者标签过滤 + topK 相似度检索封装 | `feat(rag): add tag-filtered similarity search via filter expression` |
| D17 | RAG 编排 | `MedRagAdvisorFactory`：`QuestionAnswerAdvisor` + 动态标签过滤表达式接入 ChatClient | `feat(rag): assemble question answer advisor with scoped retrieval` |
| D18 | RAG 管理接口 | `RagAdminController`：文档入库/删除/检索预览接口 + 参数校验 | `feat(rag): add rag admin endpoints for document management` |

### 阶段 3：业务能力（D19–D26）

| Day | 任务 | 实现要点 | Commit 信息 |
|---|---|---|---|
| D19 | 流式问诊 | SSE `ChatController`：Spring AI 流式输出、心跳、断连清理 | `feat(chat): add sse streaming consultation endpoint` |
| D20 | 会话服务 | 会话创建/查询/关闭/归档业务 + 分页查询 | `feat(session): add session lifecycle service and endpoints` |
| D21 | 权限校验 | `ApiKeyAuthFilter` + `PatientAccessGuard`：患者仅访问本人会话 | `feat(security): enforce patient-session ownership access control` |
| D22 | 科室注解权限 | `@RequireDept` 注解 + 拦截校验 + 403 单测 | `feat(security): add department-scope annotation based authorization` |
| D23 | 审计日志 | `@MedAudit` + AOP 切面：操作人/动作/目标/耗时落库 | `feat(audit): add aop-based medical operation audit logging` |
| D24 | 脱敏注解 | `@Desensitize(MaskType)` + Jackson 序列化器，掩码逻辑调用 Hutool `DesensitizedUtil`（手机号/身份证/病历号） | `feat(privacy): add field desensitization annotation backed by hutool` |
| D25 | 接口限流 | `@RateLimit` 注解 + AOP 切面，内部使用 Redisson `RRateLimiter`（按患者ID/接口维度） | `feat(common): add redisson rate limiter with annotation support` |
| D26 | Swagger 文档 | SpringDoc 配置、分组、鉴权说明、示例 | `docs: add openapi documentation with auth and examples` |

### 阶段 4：部署与收尾（D27–D31）

| Day | 任务 | 实现要点 | Commit 信息 |
|---|---|---|---|
| D27 | Docker 打包 | 多阶段 Dockerfile（maven build→JRE slim）、分层缓存优化 | `build: add multi-stage dockerfile with layered jar` |
| D28 | Compose 编排 | docker-compose：mysql/redis-stack/app、健康检查、初始化脚本 | `build: add docker compose stack with health checks` |
| D29 | 镜像 CI | GitHub Actions：tag 触发构建镜像并推送 GHCR | `ci: add docker image build and publish workflow` |
| D30 | 集成测试 | Testcontainers：MySQL+Redis 真实环境跑通存储与锁链路 | `test: add testcontainers integration tests for memory layer` |
| D31 | 文档收尾 | README（架构图/快速开始/徽章）、部署手册 | `docs: complete readme with architecture and deployment guide` |

### 阶段 5：运维加固（D32–D33）

| Day | 任务 | 实现要点 | Commit 信息 |
|---|---|---|---|
| D32 | 质量门禁与可观测 | JaCoCo `check` 覆盖率门禁（指令 90% / 分支 80% / 行 90%，绑定 `verify`，CI 直接阻断）；`MedStorageHealthIndicator` 探测 ShardingSphere(MySQL) + Redis 并给出分组件原因；`MedComponentInfoContributor` 通过 `/actuator/info` 暴露版本矩阵；开启 K8s liveness/readiness 探针组 | `feat(actuator): add coverage gate and storage health probes` |
| D33 | 收尾加固 | 真实 `docker build` 验证 / 英文 README / 告警接入 | `chore: finalize operations hardening` |

### 阶段 6：生产启动与真实中间件验证（D34–D36）

> 阶段 6 的出发点：D1–D33 的单测全部把 `spring.flyway.enabled` 固定为 `false` 以保证离线可跑，
> 于是"生产启动路径"从未被任何测试执行过；同时 D30 的 Testcontainers 集成测试因为
> Testcontainers 1.20.6 与 Docker Engine 的 API 版本下限冲突而被**静默跳过**。
> 两者叠加，让两个"服务在任何环境都起不来"的缺陷一直通过构建。阶段 6 专门补齐这条链路。

| Day | 任务 | 实现要点 | Commit 信息 |
|---|---|---|---|
| D34 | 生产启动迁移链路修正 | `flyway-mysql`（Flyway 10 拆分出各数据库支持模块，仅 `flyway-core` 会 `Unsupported Database: MySQL`）；Testcontainers 升至 1.21.x（Docker API 版本回退值低于 Engine 下限会让集成测试静默跳过）；`MedMigrationProperties` + `MedMigrationPool` + `MedFlywayConfig` 让 Flyway **直连物理 MySQL**（绕开 ShardingSphere 代理，代理下会 `MySQL 1007` / `Load actual table metadata` 失败），且迁移连接池**不得注册为 `DataSource` Bean**（第二个 `DataSource` 会让 MyBatis `@ConditionalOnSingleCandidate` 失去唯一候选、`SqlSessionTemplate` 无法装配）；`MedProductionStartupIntegrationTest` 用真实 MySQL 验证启动即迁移出 16 张分表 | `feat: daily iteration D34` |
| D35 | 集成测试纳入 CI | GitHub Actions 增加容器集成测试阶段（Testcontainers 可用的 runner 上真实跑 MySQL + Redis Stack）、镜像层缓存复用，避免集成套件再次静默跳过 | `ci: run the testcontainers suite in the workflow` |
| D36 | RAG 检索真实中间件验证 | Testcontainers 起 Redis Stack，验证 `RedisVectorStore` + `QuestionAnswerAdvisor` 的科室/患者 TAG 过滤检索与租户隔离（仍只做向量相似度 + 标签过滤，不解析文本） | `test: add redis stack integration test for rag retrieval` |

### 阶段 7：RAG 索引运维与检索可观测（D37–D39）

> 阶段 7 的出发点：阶段 6 证明了 RAG 链路在真实 Redis Stack 上**能**工作，但没有任何机制回答
> 「此刻它**是否还**在工作」。D36 暴露的 TAG 转义缺陷有两个共同特征——**不抛异常**、**只让检索
> 悄悄少返回数据**；索引被误删、`FT.CREATE` 因 Redis 不是 Stack 版而没建成、配置改了
> `metadata-fields` 而旧索引的 TAG 字段没跟着变，这三类事故的现场表现与它完全一样：服务健康、
> 接口 200、医生拿到一个只用指南拼出来的答案。`/actuator/health` 目前只探 MySQL 与 Redis 的
> 连通性，向量索引的**存在性**与**Schema 一致性**从未被任何探针或告警覆盖。
> 阶段 7 把「检索层可用」变成可观测、可告警、可回归的运维对象。

| Day | 任务 | 实现要点 | Commit 信息 |
|---|---|---|---|
| D37 | RAG 索引健康与 Schema 漂移检测 | `MedVectorIndexProbe` 通过官方 Jedis `FT.LIST` / `FT.INFO` 读出索引的存在性、文档数与 TAG 字段（只读元数据，不做任何向量或检索计算）；`MedVectorIndexReport` 把 `FT.INFO` 的嵌套交替列表解码为可断言的值对象并算出「配置声明但索引里没有」的 TAG 字段；`MedVectorIndexHealthIndicator` 把结果并入 `/actuator/health`（`up` / `index-missing` / `schema-drift` / `unreachable` 四种结论，文档数一并暴露）；`MedVectorIndexAlertMonitor` 复用既有 `MedAlertNotifier` 链路把降级与恢复推给日志与 Prometheus（`rag-index-degraded` / `rag-index-recovered` / `rag-index-probe-failed`）；`med.rag.index.*` 配置开关与期望 TAG 字段清单；`MedVectorIndexProbeIntegrationTest` 在真实 Redis Stack 上验证探针读到的正是 `RedisVectorStore` 建出的索引 | `feat(rag): add vector index health probe with schema drift detection` |
| D38 | 索引重建编排 | `MedVectorIndexRebuilder` 把「删索引 → 按配置重建 → 回写语料 → 验收」变成一次受控操作：Redisson `RLock`（`med:lock:rag:index:rebuild:{index}`）保证集群内互斥；删除走官方 Jedis `FT.DROPINDEX`（保留文档）或 `FT.DROPINDEX … DD`（连文档一起删）；Schema 重建**复用官方 `RedisVectorStore#afterPropertiesSet()`**（项目内无一处手写 `FT.CREATE`）；回写走普通入库路径 `MedDocumentService.ingestAll`；验收用 `MedVectorIndexProbe` 的「能否按标签检索」判据。`MedIndexRebuildMode.INDEX_ONLY` 为默认安全模式（RediSearch 会在重建时重新索引已存在的文档，因此不丢文档、不调 Embedding），`DROP_AND_REINGEST` 为破坏性模式，需 `MED_RAG_INDEX_REBUILD_ENABLED=true` **且** `MED_RAG_INDEX_REBUILD_ALLOW_DELETE=true`（能力默认关闭）；`MedIndexRebuildReport` 给出 `completed` / `verification-failed` / `failed` / `skipped-lock-held` / `refused` 与前后文档数、批次数、耗时，`MedIndexRebuildProgress` 上报阶段与批次进度，告警码 `rag-index-rebuild-completed` / `-skipped` / `-failed`（Prometheus 规则 `MedQaRagIndexRebuildFailed`）；`MedIndexRebuildIntegrationTest` 在真实 Redis Stack 上证明 `INDEX_ONLY` 确实不丢文档、`DROP_AND_REINGEST` 确实替换语料、外部持锁时重建零改动 | `feat(rag): add controlled vector index rebuild with distributed mutex` |
| D39 | 检索质量回归基线 | 冻结的**金标问题集**（`src/test/resources/rag/retrieval-baseline.json`）配冻结语料，把「检索质量」变成 CI 里会失败的断言：每条用例可断言召回（`expectedDocumentIds` + `minRecall`）、排序（`expectedTopDocumentId`）与隔离（`forbiddenDocumentIds`）三件事，`expectedDocumentIds` 为空即**反向用例**（只断言「什么都别返回」，必须显式声明 `minRecall: 0.0` 且给出非空禁止集合，专门抓标签过滤失效）。`MedRetrievalBaselineLoader` 读 JSON 并**拒绝未知字段**（Jackson 默认忽略，会让写错的键名静默退化成「没有断言」），错误信息带 JSON 路径；`MedRetrievalBaselineEvaluator` 把每个用例交给生产的 `MedRetrievalService` 执行、只比对**文档 ID**（不算相似度、不重排、不读文档内容），检索本身出错则向上抛而不是记成「召回 0」；`MedRetrievalBaselineCaseResult` / `MedRetrievalBaselineReport` 给出召回率、首个命中排名、MRR、泄漏列表与失败摘要；`MedRetrievalBaselineResourceTest` 离线守住「每条用例都能失败」与「集合不是空转的」；`MedRetrievalBaselineIntegrationTest` 在真实 Redis Stack 上跑完整集合，并用生产 `MedRetrievalFilters.matches` 校验金标集与语料不漂移 | `feat: daily iteration D39` |

### 阶段 8：安全边界与生产配置契约（D40–D43）

> 阶段 8 的出发点：2026-09-25 的全量代码审查（报告见 `.workbuddy-ai/reports/CODE_REVIEW_2026-09-25.md`）
> 在 1389 个测试全绿的前提下查出 **3 处 P0 + 6 处 P1**，三者同属一类偏差——**组件写好了但没接进调用链**、
> **注释/文档承诺的行为与代码实际行为相反**、**守护测试用前缀/范围断言，恰好放过真实缺陷**。
> 三处 P0 分别是：① `application-prod.yml` 的 `management.endpoints.web.exposure.include` 覆盖掉 base 的
> `prometheus`（Dockerfile 与 compose 都激活 `prod`，于是生产环境整条告警链静默失效）；
> ② `/api/chat/stream` 的会话身份与 RAG scope 全部取自**未校验的请求体**（患者 A 填患者 B 的
> tenant/dept/session 即可越权读写他人会话）；③ `RagAdminController` 的 scope 同样取自请求体，
> 且 `MedDocumentService.deleteByIds` 没有任何 scope 谓词（任意 STAFF Key 可跨科室增删向量）。
> 阶段 8 逐条收敛这些边界，并把「跨组件契约」从注释承诺变成可执行断言。

| Day | 任务 | 实现要点 | Commit 信息 |
|---|---|---|---|
| D40 | 生产配置契约与守护断言校正 | `application-prod.yml` 的暴露列表修正为 `health,info,prometheus`（Spring Boot 的 list 属性在 profile 里是**覆盖**而非合并，收窄即等于生产环境 404 掉 `/actuator/prometheus`，Prometheus 抓不到 `med_qa_alert_total`、`deploy/prometheus/med-qa-alerts.yml` 里全部规则永不触发）；新增跨文件契约测试 `ApplicationProfileContractTest`——用 Spring Boot 自己的 `YamlPropertySourceLoader` 按 profile 优先级合并 base 与 `application-<profile>.yml`，再用 `Binder` 绑定**实际生效值**，断言「任何 profile 都不得移除 base 已暴露的端点」，并锁定 profile 集合与 Dockerfile/compose 实际激活的 profile，使断言不会因 profile 改名而空转；`DeploymentDocumentationTest` 的前缀断言 `include: health,info` 改为完整串（前缀断言正是 P0-1 的漏网原因）并补 `application-prod.yml` 断言；`CoverageGateConfigTest` 的 `isBetween(0.0, 1.0)` 改为显式下限（把门禁调到 0.01 也能通过的守卫等于不守） | `fix(config): keep prometheus exposed in the prod profile` |
| D41 | 流式问诊身份来源收敛 | `/api/chat/stream` 的 tenant/dept/patient 一律取自已认证的 `MedPrincipal`，请求体中的身份字段降级为**一致性校验**（不匹配即 403），`ChatStreamService` 在追加轮次前先过 `PatientAccessGuard` 与 `MedChatSessionService.requireWritableSession`（该方法此前零生产调用点，注释却声称流式路径会调它，已关闭/归档的会话因此仍可增长） | `fix(security): derive streaming consultation identity from the principal` |
| D42 | RAG 管理端授权与按 scope 删除 | `RagAdminController` 的 ingest / delete / search 三处 scope 全部由 principal 推导（复用 D41 的 `RequestIdentityGuard`，请求体只作一致性校验：缺省按 principal 取值、不一致即拒绝），消除「猜到 documentId 即可跨科室物理删除向量」。**删除只保留 scope 一种模式**：`MedDocumentService` 的 `deleteById` / `deleteByIds` 被**删除**而不是加守卫——向量库用 id 作 key、隔离标签在 JSON 值里、RediSearch 不索引 key，所以「这批 id 且属于我的 scope」无法表达成单个过滤表达式，无 scope 谓词的 `VectorStore#delete(List)` 永远做不成 scope 安全；为此加反射守护测试钉住「该原语不存在」。部门级删除（会连共享指南一起删）必须显式 `confirmDepartmentWide=true`，与 D38 的「两道开关」同源；控制器自己再判一次 STAFF，因为 `MED_SECURITY_DEPT_SCOPE_ENABLED=false` 会让 `@RequireDept` 拦截器整体跳过而认证仍在；新增跨组件契约测试 `RagAdminAuthorizationContractTest`（真实 API Key → 拦截器 → 控制器 → 守卫 → 服务，断言到达服务的是 principal 的坐标），并亲眼看过它变红（还原「body 优先」后 12 个用例失败） | `fix(rag): scope rag admin operations to the caller` |
| D43 | 告警投递顺序与探测失败信号 | `MedAlertNotifier` 改为**投递成功后才写冷却指纹**（原实现先 `putIfAbsent` 再 `dispatch`，所有 sink 同时失败时该告警在整个冷却窗口内被静默丢弃）；`MedVectorIndexAlertMonitor` 的 `INDEX_PROBE_FAILED`（CRITICAL）分支原为不可达——`AbstractHealthIndicator#health()` 是 final 且把异常吞成 `DOWN`，故把「探测本身失败」做成**显式信号**：`reason=unreachable` 由 `MedVectorIndexHealthIndicator` 自己写入，监控读该 reason 分流（`unreachable` → CRITICAL，`index-missing`/`schema-drift` → WARNING），并新增 Prometheus 规则 `MedQaRagIndexProbeFailed`；新增跨组件契约测试 `AlertDeliveryContractTest`（真实探针 → 健康组件 → 监控 → 通知器 → sink） | `fix(alert): stop marking an alert as delivered before it is` |

> 阶段 8（D40–D43）**已完成**：三处 P0（prod profile 暴露契约、流式身份来源、RAG 管理端授权）与
> P1-4 / P1-5 / P1-6 均已收敛，并各自配套跨组件契约测试（`ApplicationProfileContractTest` /
> `StreamingIdentityContractTest` / `RagAdminAuthorizationContractTest` / `AlertDeliveryContractTest`），
> 每一条都按项目惯例**先被亲眼看过它变红**。
>
> **阶段 8 之后仍待处理（见阶段 9）**：P1-1 `metadata-mode: EMBED`
> 实际**包含**元数据（与上方注释相反，隔离标签会被写进向量）、P1-2 MySQL 只保存
> `MessageWindowChatMemory` 的 20 条滚动窗口（javadoc 却称其为 "authoritative copy"）、P1-3 记忆写路径
> 无锁，以及 P2-1/P2-3/P2-4 的注释与部署前置条件校正。

### 阶段 9：会话轨迹持久化与检索元数据隔离（D44–D46）

> 阶段 9 的出发点：2026-09-25 全量审查在阶段 8 收敛掉 3 处 P0 与 P1-4/5/6 之后，仍留下
> P1-1 / P1-2 / P1-3 三条。三者依旧是同一种偏差——**注释与文档承诺的行为和代码实际行为相反**，
> 只是这次偏差发生在**数据本身**上：P1-2 让「权威副本」只有 20 条，超出窗口的病历轨迹被
> `saveAll` 的「先删后插」永久删除；P1-1 让「标签不进入向量」的注释与实际把元数据拼进
> embedding 输入的行为相反，隔离标签污染了相似度；P1-3 让窗口写入在跨实例并发下没有互斥。
> 阶段 9 把这三条从「注释承诺」改成「代码事实」，并各配一条跨组件契约测试。

| Day | 任务 | 实现要点 | Commit 信息 |
|---|---|---|---|
| D44 | 会话轨迹持久化契约 | MySQL 必须保存**完整病历轨迹**，而不是 `MessageWindowChatMemory` 的 20 条滚动窗口：`MedSpringAiChatMemoryRepository.saveAll` 不再「先 `deleteSession` 再 `appendAll`」（该顺序把落在窗口外的历史消息物理删除，且并发两轮会互相清空），改为经 Redisson `RLock` 串行化的**幂等追加**——新增 `ChatMessageMapper.insertIfAbsent`（`INSERT … ON DUPLICATE KEY UPDATE message_id = message_id`，按主键去重，`1` = 新插入、`0` = 已存在），`MedChatMemoryRepository.saveWindow` 只做「已存在则跳过」的追加、随后把该窗口发布到 Redis 缓存，**任何路径都不再删除**；同时把**冷缓存回源**截断到缓存窗口（`RedisMessageCache#windowSize`）——D44 之后 MySQL 存的是完整轨迹，不截断就会把整段历史灌进模型 prompt。窗口裁剪仍完全由官方 `MessageWindowChatMemory` 负责，项目零自研窗口逻辑。**不引入 `@Transactional`**：写入幂等且 Spring AI 每轮都会重发整个窗口，部分失败会被下一轮自愈，而在事务内刷新 Redis 缓存反而会在回滚后留下「缓存有、库中没有」的脏窗口——该取舍写在 `saveWindow` 的 javadoc 里。契约测试：`ChatMemoryWindowIntegrationTest` 断言「窗口只交 2 条、持久层留 5 条」，`MedStorageAndLockIntegrationTest` 用真实 MySQL + Redis 断言窗口 4 条而分表 10 行。**该契约测试当场查出第二个缺陷并当次修掉**：`med_message.patient_id` 是 `NOT NULL`，而 Spring AI 的消息不带任何项目身份（患者 id 不在 `tenant:dept:session` 里、助手消息由框架构造），于是生产的每一轮问诊写入都会以 `Column 'patient_id' cannot be null` 失败——此前单测全 mock mapper、集成测试全用自带 patientId 的 `sampleMessage`，没有一个测试走到「框架消息落库」；修法是桥接层写入前用 `ChatSessionMapper.selectById` 解析会话所属患者（消息自带者优先，会话缺失/无患者则 `STORAGE_ERROR` 拒绝） | `fix(memory): keep the full transcript instead of the memory window` |
| D45 | 检索元数据不进入向量文本 | `spring.ai.openai.embedding.metadata-mode` 由 `EMBED` 改为 `NONE`——Spring AI 1.0.0 的 `OpenAiEmbeddingModel.embed(Document)` 走 `getFormattedContent(metadataMode)`，`DefaultContentFormatter.metadataFilter` 把 `EMBED` 解释为「全部键减去 `excludedEmbedMetadataKeys`」，因此现配置把 `tenant_id`/`dept_id`/`patient_id` 一并拼进 embedding 输入，既污染相似度又把隔离标签泄进向量；配套契约测试用 `Binder` 绑定**实际生效值**并断言为 `NONE`，再断言 `metadata-fields` 的 TAG 项仍齐全（过滤能力不因 `NONE` 而丢失） | `fix(rag): stop embedding isolation metadata into the vector` |
| D46 | 记忆层注释与部署前置条件校正 | P2-1/P2-3/P2-4：`MedChatMemoryRepository` / `RedisMessageCache` 的窗口语义与 `med.cache.max-messages` 注释对齐（D44 已顺手改掉「读路径返回整段会话」与两处 yml 注释，本日只收剩余项）；`docs/DEPLOYMENT.md` 补「`MED_CHAT_MAX_MESSAGES` 只影响送进模型的消息条数，不影响落库轨迹」的运维说明，并校正部署前置条件表述 | `docs(memory): correct the memory tier documentation` |

> 阶段 9 进度：**D44 / D45 / D46 全部完成，阶段 9 收尾**。D44 收敛 P1-2 / P1-3，D45 收敛 P1-1，
> D46 收敛 P2-1 / P2-3 / P2-4，三条都按项目惯例**先被亲眼看过它变红**。
>
> D45 由两处改动构成：`application.yml` 的 `metadata-mode` 由 `EMBED` 改为 `NONE`（`EMBED` 同时是 Spring AI
> `OpenAiEmbeddingProperties.metadataMode` 的**框架默认值**，所以「键缺失」同样不安全），以及
> `EmbeddingModelConfig.SAFE_METADATA_MODE` 把代码侧回落值同步收敛为 `NONE`；跨组件契约
> `EmbeddingMetadataContractTest` 用 `YamlPropertySourceLoader` + `Binder` 绑定**实际生效值**（逐 profile
> 叠加），并用真实 `Document#getFormattedContent` 演示同一个文档在 `NONE` 与 `EMBED` 下嵌入文本的差别，
> 从而把「为什么这个配置值重要」从注释变成可执行断言。
>
> D46 只做口径校正、不改行为，三处收尾：① `MedChatMemoryRepository#findAll` 的 javadoc 仍写着读路径
> 「重放整段轨迹」，与紧随其后的「答案受 `med.cache.max-messages` 约束」自相矛盾——现已写明
> `findAll` 返回有界窗口、`reload` 是全仓库唯一返回完整轨迹的读，`RedisMessageCache` /
> `MedCacheProperties` / `MedChatMemoryProperties` 同步声明「`med.cache.max-messages` 只约束缓存与冷缓存
> 回源、`med.chat.max-messages` 只约束 prompt、两者都不约束落库轨迹」，并点名
> `MED_CACHE_MAX_MESSAGES=0` 会同时解除两种截断；② `docs/DEPLOYMENT.md` 把「MySQL 库默认字符集必须
> `utf8mb4`」升级为显式前置条件（V1 DDL 为兼容 H2 刻意不 pin `DEFAULT CHARSET`；Compose 靠
> `--character-set-server` 兜底；连接串 `characterEncoding` 必须写 Java 字符集名 `UTF-8`），并说明 H2
> 留在生产 classpath 是 `runtime` scope 的权衡且 **H2 Console 在任何配置中都未开启**；③ 脱敏层三处
> 「本项目零手写掩码」与 `MaskType.maskKeepEdges` 冲突，改为「手机号/身份证委托 Hutool、病历号因
> Hutool 无对应策略而保留首尾」。守护测试：`MemoryWindowSemanticsTest`（含**已废弃措辞不得回归**的
> 反向断言）、`DeploymentPrerequisiteTest`（扫描 `src/main/resources/**` 证明无配置开启 H2 Console、
> `pom.xml` 的 H2 仍是 `runtime`、compose 确实 pin `utf8mb4`）、`PrivacyMaskingDocumentationTest`。
>
> **阶段 9 之后没有 D47**：下次运行必须先扩写本路线图（新增阶段 10）再实现，不得自造迭代编号。
> 候选方向：把「文档/注释与代码行为一致」这类偏差做成更系统的检查、或补齐仍未覆盖的运行时能力。

### 阶段 10：会话生命周期治理与保留策略（D47–D48）

> 阶段 10 的出发点：`MedChatSessionService#archiveSession` 的 javadoc 从 D20 起就写着会诊有两种结束
> 方式——「显式关闭，或**保留策略作业扫描陈旧会话**」——但**这个作业从来不存在**。于是
> `SessionStatus#ARCHIVED` 只能由一次显式 API 调用到达：患者开了会话问一句就再也不回来，那一行就
> 永远停在 `ACTIVE`，它的 Redis 窗口也永远温热（TTL 到期后每次读都会从 MySQL 回源重新灌满），
> `med_session` 只增不减，而「陈旧会诊自动转冷」这条写在注释里的运维承诺在代码里没有任何对应物。
> 这仍然是阶段 8/9 那一类偏差——**注释承诺了代码没有的行为**——只是这次承诺的是一个后台能力，
> 而且它的缺失不会让任何测试变红。
>
> 阶段 10 把它变成真的，并回答随之而来的两个问题：**自动归档凭什么不会误伤正在进行的会诊**
> （答案必须是「归档语句自身带陈旧谓词」，而不是「作业跑得快」），以及**它凭什么不能直接复用
> `archiveSession`**（答案见 D47：请求态的 `PatientAccessGuard` 对无主体调用 fail-closed，
> 后台作业没有主体）。

| Day | 任务 | 实现要点 | Commit 信息 |
|---|---|---|---|
| D47 | 陈旧会话自动归档（保留策略） | 把「陈旧的 ACTIVE 会话自动转 `ARCHIVED`」做成一次受控的后台扫描。**不能复用 `MedChatSessionService#archiveSession`**：它先经 `findSession` → `PatientAccessGuard.assertOwned`，而该守卫在**无主体**时 fail-closed 抛 `FORBIDDEN`（D21 的设计），后台作业恰恰没有主体——于是每个候选都会被拒。**也不能在 `MedChatSessionService` 上开一个「跳过守卫」的公开方法**：那等于给请求态路径发一把无守卫的归档原语，正是 D41/D42 收敛掉的那类缺口。因此 `MedSessionRetentionService`（`com.med.qa.service`）自带一条系统态归档路径，只复用同源协作者（`ChatSessionMapper` + `SessionLockService` + `RedisMessageCache`），并把「为什么另起一条」写进 javadoc。**防误伤靠 SQL 谓词而不是靠时序**：新增 `ChatSessionMapper#updateStatusIfStale(sessionId, status, expectedStatus, idleBefore, updatedAt)`，`UPDATE … WHERE session_id = ? AND status = ACTIVE AND updated_at <= :idleBefore` —— 陈旧判据与状态迁移在同一条语句里原子完成，即使有绕过会话锁的写入者也归档不了一个刚被问过的会话；会话锁（与消息写入路径同一把）再保证「读候选 → 归档」之间不会有新消息插进来。扫描本身用 `selectStaleActiveSessions(idleBefore, limit)`（跨租户，`status = ACTIVE AND updated_at <= ?` 按 `updated_at` 升序，只走一条 `status, updated_at` 索引，故 V4 迁移补 `idx_med_session_retention`）与 `countStaleActiveSessions(idleBefore)`（给出积压量）。集群内互斥用 Redisson `RLock`（键 `med:lock:session:retention`，`tryLock` 失败即报 `skipped-lock-held` 而不碰 MySQL，与 D38 同源）；`med.session.retention.*` 默认 `enabled=false`（可选能力，必须有开关）**且** `dry-run=true`（第一版部署只报告不动作，与 D38 的两道开关同源），`dry-run` 下扫完一批即停——因为什么都没改，再扫一批只会拿到同一批行；`batch-size`/`max-batches` 给单次运行封顶。`SessionRetentionReport` 给出 `completed`/`skipped-lock-held` 与 `candidates`/`archived`/`skipped`/`failed`/`batches`/`remaining`/`durationMillis`/`failures`（单个候选失败不中断整轮，计入 `failed`）。`MedSessionRetentionScheduler` 按 `check-interval` 驱动并复用既有 `MedAlertNotifier` 链路（`session-retention-completed` INFO 只在真的归档了东西时发、`session-retention-failed` WARNING），Prometheus 规则 `MedQaSessionRetentionFailed`；`MedSessionRetentionConfig` 单独声明 `@EnableScheduling`，因为 `MedAlertConfig` 只在 `med.alert.enabled=true` 时才装配调度器。守护测试：`MedSessionRetentionServiceTest`（含「锁被占时不碰 MySQL」「dry-run 零写入」「CAS 返回 0 记 skipped」）、`ChatSessionMapperRetentionShardingTest`（H2 + Flyway + ShardingSphere 真跑三条新语句，钉住 `<=` 与状态码映射）、`MedSessionRetentionConfigTest`（开关真的增删 Bean，且 `@Scheduled` 占位符键在 application.yml 里真实存在——否则作业会静默退回注解默认值、运维改了配置不生效） | `feat(session): archive stale consultation sessions on a schedule` |
| D48 | 已归档会话轨迹的冷归档导出 | D47 只把 `med_session.status` 翻成 `ARCHIVED` 并丢掉 Redis 窗口——**轨迹本身一行没动**：它仍躺在 16 张 `med_message_*` 热分表里，冷热分层只完成了一半，而「归档了」这句话在存储层没有任何可验证的凭据（既没有冷副本，也没有一份能证明冷副本完整的摘要）。D48 补上这一半：把已归档会话的**完整轨迹**按统一存储规范（Protobuf 二进制）复制到非分片的冷表 `med_message_archive`，并在 `med_session_archive` 写一行**导出清单**（`message_count` + `payload_checksum` + `exported_at`），使「这份冷副本与源轨迹一致」从一句承诺变成一次可重算的比较。**为什么另起一套表而不是复用热表**：冷表不参与 `crc32(session_id)%16` 分片，按 `(session_id, created_at, message_id)` 聚簇，一次会话的轨迹是一条连续范围读，而不是散落在分片里；它同时是**异构系统可互读互迁**承诺（路线图第四节）的第一个可执行落点——存的是冻结的 Protobuf 字节，Python 中间件用自己的 `med_session.proto` 就能解。**为什么导出不会抄到一半的轨迹**：`ARCHIVED` 是终态且写入路径在每一轮问诊前都过 `MedChatSessionService#requireWritableSession`（`SessionStatus#isWritable()` 只对 `ACTIVE` 为真），所以归档会话的轨迹是静默的；但这条不变量**属于另一个组件**，所以导出不靠「相信它」——先读一遍源轨迹，写完冷表后再读一遍源轨迹，两次的摘要必须一致（少一条、多一条、换序、正文改动都会改变摘要），否则记 `mismatched` 并**拒绝写清单**（下轮仍是候选，幂等追加只补缺的那几条，收敛）。**校验和**用标准 SHA-256（零自研）：逐条按 `message_id \t created_at \t sha256hex(payload)` 组成规范行，再对全部规范行做一次 SHA-256——空轨迹的摘要是 `sha256("")`，与「没有导出过」区分得开。**幂等靠主键而不是靠查重**：`med_message_archive` 主键 `(session_id, message_id)`，写入用 `INSERT … ON DUPLICATE KEY UPDATE message_id = message_id`（与 D44 的 `insertIfAbsent` 同源，**不用 `INSERT IGNORE`**）；`med_session_archive` 主键 `session_id`，清单插入返回 0 行即记 `skipped`（别的副本已导过）。**候选查询用 `NOT EXISTS` 而不是先翻页再过滤**：`selectUnexportedArchivedSessions(status, limit)` = `med_session s WHERE s.status = ? AND NOT EXISTS (SELECT 1 FROM med_session_archive a WHERE a.session_id = s.session_id) ORDER BY s.updated_at ASC, s.session_id ASC LIMIT ?`——若改成「按 `updated_at` 翻一页再逐行问有没有清单」，最旧的那批恰好就是已导出的那批，作业会永远原地打转。**排序必须全序**：热表与冷表都按 `(created_at, message_id)` 排（新增 `ChatMessageMapper#selectTranscriptBySessionId`，与喂给记忆窗口的 `selectBySessionIdOrderByCreatedAtAsc` 分开，后者只需要「够用」的顺序），否则同毫秒写入的两条消息在两次读里顺序可能不同，摘要会假失败。**不做删除**：D48 只增不删，热表一行不动（D47 的类注释承诺「误归档可以改回来」，删热行会让这句话失效）；真正冷热分层的「清」需要自己的守卫与自己的迭代，本阶段没有 D49，不得顺手实现。`med.session.archive.*` 默认 `enabled=false` **且** `dry-run=true`（与 D38/D47 同源；dry-run 下扫完一批即停），集群互斥用 Redisson `RLock`（键 `med:lock:session:archive:export`），`batch-size`/`max-batches` 封顶单次运行，`SessionArchiveExportReport` 给出 `completed`/`skipped-lock-held` 与 `candidates`/`exported`/`skipped`/`mismatched`/`failed`/`batches`/`remaining`/`durationMillis`/`failures`；`MedSessionArchiveExportService#verify(sessionId)` 是运维可点名的单会话复核（`VERIFIED`/`MANIFEST_MISSING`/`TRANSCRIPT_CHANGED`/`COLD_COPY_DIFFERS`），与导出共用同一套摘要计算，避免「导出的校验」和「复核的校验」是两套口径。`MedSessionArchiveExportScheduler` 复用既有 `MedAlertNotifier` 链路（`session-archive-completed` INFO 只在真的导出了东西时发、`session-archive-failed` WARNING、`session-archive-mismatch` WARNING——**「冷副本与源轨迹不一致」是数据完整性问题，不 page 但绝不静默**），Prometheus 规则 `MedQaSessionArchiveExportFailed` / `MedQaSessionArchiveMismatch`；`MedSessionArchiveConfig` 自带 `@EnableScheduling`（与 D47 同因：`MedAlertConfig` 只在 `med.alert.enabled=true` 时装调度器）。V5 迁移建两张冷表（`med_message_archive` / `med_session_archive`，均为 `!SINGLE` 非分片表），**新增迁移必须同步** `MedProductionStartupIntegrationTest` 迁移计数（4→5）与 `DeploymentDocumentationTest` 的 `Flyway V1–V5` + `hasSize(5)` + 手册 §6 表格。守护测试：`MedSessionArchiveExportServiceTest`（dry-run 零写入、锁被占时不碰 MySQL、清单 CAS 返回 0 记 `skipped`、源轨迹在导出中途变化时**拒绝写清单**、单会话失败不中断整轮）、`SessionArchiveMapperShardingTest`（H2 + Flyway + ShardingSphere 真跑 V5 建表、`NOT EXISTS` 候选查询排除已导出会话、`insertIfAbsent` 幂等、清单 CAS）、`MedSessionArchiveConfigTest`（开关真的增删 Bean，且 `@Scheduled` 占位符键在 application.yml 里真实存在） | `feat(session): export archived consultation transcripts to a cold store` |

> 阶段 10 进度：**D47 / D48 全部完成，阶段 10 收尾**。D47 让「陈旧会话自动归档」从注释变成作业，
> D48 让「归档」在存储层第一次有了可验证的凭据（冷副本 + 摘要 + 清单）。
>
> **阶段 10 之后没有 D49**：下次运行必须先扩写本路线图（新增阶段 11）再实现，不得自造迭代编号。
> D48 **刻意没有**实现冷表的清理（把热分表里的行删掉）：那是不可逆操作，需要自己的守卫、
> 自己的开关和自己的迭代，而路线图里没有给它编号。

---

## 四、统一存储对接规范（与外部 Python 中间件字段级对齐，代码零依赖）

本服务不依赖任何外部项目代码，仅遵循同一份 `med_session.proto` 协议规范：

| 规范项 | 约定 |
|---|---|
| 消息字段 | `message_id(UUIDv7)` / `session_id` / `tenant_id` / `dept_id` / `patient_id` / `role` / `content` / `token_count` / `masked` / `created_at(epoch millis)` / `metadata` |
| Redis 键 | `med:chat:{tenant_id}:{dept_id}:{session_id}` |
| MySQL 分表 | `med_message_{crc32(session_id) % 16}`，16 张分表（由 ShardingSphere `Crc32ShardingAlgorithm` 保证与规范一致） |
| 序列化 | Protobuf 二进制落库；API 层 DTO 用 JSON |
| 角色枚举 | PATIENT=0 / DOCTOR=1 / ASSISTANT=2 / SYSTEM=3 |

由此保证：两套系统写入的会话数据可互读互迁，字段、键、分表路由、编码完全一致。

---

## 五、Commit 提交规范（Conventional Commits）

```
<type>(<scope>): <subject 英文小写祈使句，≤72字符>

[body 可选：动机 + 方案要点]
[footer 可选：BREAKING CHANGE / issue 引用]
```

- type：`feat` / `fix` / `refactor` / `perf` / `test` / `docs` / `ci` / `build` / `chore`
- scope：`common` / `domain` / `memory` / `rag` / `chat` / `session` / `security` / `audit` / `privacy`
- 铁律：**每个迭代必须配套 JUnit5 单元测试，`mvnw test` 全绿才允许提交**

### 每日分模块批次提交与推送流程

每天迭代完成后，按模块包分批提交，形成清晰提交历史，最后统一推送：

```bash
# 1. 检查变更
git status
# 2. 按模块分批提交（示例：某迭代同时改了 memory 与测试）
git add src/main/java/com/med/qa/memory/           && git commit -m "feat(memory): add redis message cache with spec-compliant key schema"
git add src/main/resources/db/ src/main/resources/mapper/ && git commit -m "feat(memory): add flyway sharded table ddl and mybatis mappers"
git add src/test/java/com/med/qa/memory/           && git commit -m "test(memory): add message cache unit tests"
git add pom.xml .github/                           && git commit -m "build: add shardingsphere and redisson dependencies"   # 如有
# 3. 推送
git push origin main
```

分批顺序约定：`common/config → domain → memory → rag → service → security → audit → privacy → controller → resources → test → 构建/CI 文件`。禁止一天所有变更混在一个大 commit 里推送。

---

## 六、简历技术亮点（本项目）

1. 基于 Spring AI 官方 `ChatMemory` 体系实现自定义 `ChatMemoryRepository`：ShardingSphere-JDBC 16 分表 + Redis 缓存 cache-aside 双层存储，Protobuf 统一序列化，与异构 Python 存储中间件实现字段级数据互通
2. 基于 Spring AI `RedisVectorStore` + `QuestionAnswerAdvisor` 构建医疗 RAG：科室/患者 ID 元数据 Filter Expression 过滤检索，实现租户级数据隔离的向量召回
3. 使用 ShardingSphere-JDBC 自定义分片算法插件（crc32 % 16），实现与异构系统一致的透明分表路由
4. 基于 Redisson 实现分布式会话锁（RLock 看门狗续期）与注解式接口限流（RRateLimiter），保障并发问诊写入一致性
5. 落地医院级安全合规：患者-会话归属校验、科室注解权限、AOP 操作审计、`@Desensitize` 隐私字段脱敏注解（Hutool）
6. SSE 流式 LLM 问诊接口 + Testcontainers 集成测试 + JaCoCo 覆盖率 + GitHub Actions 自动构建 Docker 镜像推送 GHCR，生产级 DevOps 闭环
