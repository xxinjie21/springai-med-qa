# springai-med-qa 开发路线图

> 医院生产级 AI 问诊后端服务（Java / Spring Boot 3 / Spring AI）
> 仓库路径：`D:\javaproject\springai-med-qa` ｜ 独立仓库，独立推送 GitHub

---

## 一、项目简介

**定位**：医院生产级 AI 问诊后端服务，自定义 Spring AI `ChatMemory` 落地统一医疗会话存储规范（字段与序列化协议与 med-langchain-memory 对齐，但代码零依赖、完全独立），自研纯向量数学的本地 RAG 向量库，专注业务层、权限控制、向量检索、流式 LLM 问答、数据脱敏与容器化部署。

**核心能力**：
- 自定义 `ChatMemory`：MySQL 分表 + Redis 缓存双层存储，Protobuf 序列化，字段规范与统一存储协议完全对齐
- 自研本地医疗 RAG 向量库：仅底层向量数学（余弦/L2/点积）、元数据标签过滤检索（科室/患者ID 标签，**不解析文本内容**）、Top-K 堆检索、LRU 向量内存淘汰、文件快照持久化
- 流式问诊接口（SSE）、患者会话权限校验、Redis 分布式锁、医疗操作日志审计（AOP）、隐私字段脱敏注解
- Docker 多阶段打包、GitHub Actions 自动构建镜像推送、Swagger/OpenAPI 文档

**明确不做**：无任何文本预处理客户端、无内容解析拦截器；Embedding 向量一律由外部模型 API 生成，本服务只做向量数学与标签过滤。

**技术栈**：Java 17 / Spring Boot 3 / Spring AI / MyBatis / MySQL / Redis / Protobuf / Spring Security / SpringDoc / JUnit5+Mockito / Testcontainers / Docker / GitHub Actions

---

## 二、完整分层目录结构

```
springai-med-qa/
├── pom.xml
├── README.md
├── ROADMAP.md                      # 本文件
├── Dockerfile                      # 多阶段构建
├── docker-compose.yml              # mysql + redis + app
├── .github/workflows/
│   ├── ci.yml                      # mvn verify + jacoco
│   └── docker-publish.yml          # 构建并推送镜像至 GHCR
├── src/main/proto/
│   └── med_session.proto           # 统一序列化协议（与存储规范同源副本）
├── src/main/java/com/med/qa/
│   ├── MedQaApplication.java
│   ├── config/                     # 配置层
│   │   ├── RedisConfig.java
│   │   ├── SecurityConfig.java
│   │   ├── SpringAiConfig.java
│   │   └── OpenApiConfig.java
│   ├── common/                     # 通用层
│   │   ├── result/ApiResult.java   # 统一响应体
│   │   ├── exception/              # 全局异常处理
│   │   └── util/
│   ├── domain/                     # 领域层
│   │   ├── entity/                 # ChatSessionDO / ChatMessageDO / AuditLogDO
│   │   ├── enums/                  # RoleType / SessionStatus
│   │   └── dto/                    # 请求/响应 DTO
│   ├── memory/                     # 自定义 ChatMemory 层
│   │   ├── MedChatMemory.java      # 实现 Spring AI ChatMemory
│   │   ├── serde/ProtoMessageCodec.java
│   │   ├── store/                  # 存储适配（适配器+工厂）
│   │   │   ├── MemoryStore.java    # 接口
│   │   │   ├── MemoryStoreFactory.java
│   │   │   ├── MysqlMemoryStore.java
│   │   │   ├── RedisMemoryStore.java
│   │   │   └── CompositeMemoryStore.java  # Redis缓存+MySQL持久双写
│   │   └── lock/SessionLockManager.java   # Redis 分布式锁
│   ├── rag/                        # 自研向量库（纯向量数学）
│   │   ├── math/VectorMath.java    # 余弦/L2/点积
│   │   ├── core/
│   │   │   ├── VectorRecord.java   # 向量 + 元数据标签
│   │   │   ├── InMemoryVectorStore.java
│   │   │   ├── MetadataFilter.java # 科室/患者ID 标签过滤
│   │   │   ├── TopKSearcher.java   # 最小堆 Top-K
│   │   │   └── LruEvictionPolicy.java
│   │   ├── persist/SnapshotPersister.java # 文件快照
│   │   └── embedding/EmbeddingClient.java # 外部 embedding API 适配
│   ├── mapper/                     # MyBatis Mapper + 分表路由
│   │   ├── ChatMessageMapper.java
│   │   └── sharding/TableShardRouter.java
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
│   ├── privacy/                    # 脱敏层
│   │   ├── annotation/Desensitize.java
│   │   ├── DesensitizeSerializer.java  # Jackson 序列化器
│   │   └── MaskStrategy.java       # 手机号/身份证/病历号策略枚举
│   └── controller/
│       ├── ChatController.java     # SSE 流式问诊
│       ├── SessionController.java
│       └── RagAdminController.java
├── src/main/resources/
│   ├── application.yml
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
| D1 | 项目脚手架 | Spring Boot 3 + 分层包结构、application.yml 多环境、.gitignore | `chore: bootstrap spring boot skeleton with layered packages` |
| D2 | 统一响应与异常 | `ApiResult<T>`、全局 `@RestControllerAdvice`、错误码枚举 + 单测 | `feat(common): add unified api result and global exception handler` |
| D3 | 领域实体 | `ChatSessionDO`/`ChatMessageDO` 字段严格对齐统一存储规范 | `feat(domain): add session and message entities aligned with storage spec` |
| D4 | Protobuf 集成 | 引入 protobuf-maven-plugin，编译 med_session.proto，生成类单测 | `feat(proto): integrate protobuf codegen for unified session schema` |
| D5 | CI 流水线 | GitHub Actions：mvn verify + JaCoCo 覆盖率报告上传 | `ci: add maven verify workflow with jacoco coverage` |

### 阶段 1：自定义 ChatMemory（D6–D12）

| Day | 任务 | 实现要点 | Commit 信息 |
|---|---|---|---|
| D6 | Store 抽象+工厂 | `MemoryStore` 接口 + `MemoryStoreFactory`（配置驱动，适配器模式） | `feat(memory): define memory store interface with factory` |
| D7 | Proto 编解码 | `ProtoMessageCodec`：DO↔protobuf 双向转换 + round-trip 单测 | `feat(memory): add protobuf codec aligned with unified schema` |
| D8 | MySQL 存储 | MyBatis Mapper、Flyway 16 张分表 DDL、`crc32(session_id)%16` 路由 | `feat(memory): implement mysql store with hash-based table sharding` |
| D9 | Redis 缓存 | `RedisMemoryStore`：键规范 `med:chat:{tenant}:{dept}:{session}`、TTL | `feat(memory): implement redis store with spec-compliant key schema` |
| D10 | 双层复合存储 | `CompositeMemoryStore`：Redis 读优先、MySQL 兜底、异步回填 | `feat(memory): add composite store with cache-aside strategy` |
| D11 | 分布式锁 | `SessionLockManager`：SETNX+lua 释放+看门狗续期 + 并发单测 | `feat(memory): add redis distributed session lock with watchdog` |
| D12 | ChatMemory 实现 | `MedChatMemory implements ChatMemory`，接入 Spring AI 会话链路 | `feat(memory): implement spring ai chat memory over composite store` |

### 阶段 2：自研 RAG 向量库（D13–D20）

| Day | 任务 | 实现要点 | Commit 信息 |
|---|---|---|---|
| D13 | 向量数学 | `VectorMath`：余弦/L2/点积，边界处理（零向量/维度不符）+ 精度单测 | `feat(rag): add vector math utilities with cosine and l2 distance` |
| D14 | 向量记录与存储 | `VectorRecord`（float[]+标签 map）、线程安全 `InMemoryVectorStore` | `feat(rag): add in-memory vector store with concurrent access` |
| D15 | 标签过滤 | `MetadataFilter`：科室/患者ID 等标签 AND/OR 过滤（仅元数据匹配） | `feat(rag): add metadata tag filtering for dept and patient scope` |
| D16 | Top-K 检索 | 最小堆 Top-K + 过滤前置短路优化 + 基准测试 | `feat(rag): implement heap-based top-k similarity search` |
| D17 | LRU 淘汰 | 容量上限 + LRU 淘汰策略（LinkedHashMap/自研双向链表） | `feat(rag): add lru eviction policy for vector store capacity control` |
| D18 | 快照持久化 | 向量库文件快照保存/加载（protobuf 编码）、启动自动恢复 | `feat(rag): add snapshot persistence with startup recovery` |
| D19 | Embedding 客户端 | 外部 embedding API 适配器（WebClient、重试、超时熔断） | `feat(rag): add external embedding api client with retry` |
| D20 | RAG 编排 | `RagService`：检索→按标签过滤→拼接 prompt 上下文（不解析文本） | `feat(rag): assemble retrieval pipeline with tag-scoped context` |

### 阶段 3：业务能力（D21–D28）

| Day | 任务 | 实现要点 | Commit 信息 |
|---|---|---|---|
| D21 | 流式问诊 | SSE `ChatController`：Spring AI 流式输出、心跳、断连清理 | `feat(chat): add sse streaming consultation endpoint` |
| D22 | 会话服务 | 会话创建/查询/关闭/归档业务 + 分页查询 | `feat(session): add session lifecycle service and endpoints` |
| D23 | 权限校验 | `ApiKeyAuthFilter` + `PatientAccessGuard`：患者仅访问本人会话 | `feat(security): enforce patient-session ownership access control` |
| D24 | 科室注解权限 | `@RequireDept` 注解 + 拦截校验 + 403 单测 | `feat(security): add department-scope annotation based authorization` |
| D25 | 审计日志 | `@MedAudit` + AOP 切面：操作人/动作/目标/耗时落库 | `feat(audit): add aop-based medical operation audit logging` |
| D26 | 脱敏注解 | `@Desensitize(strategy)` + Jackson 序列化器：手机号/身份证/病历号 | `feat(privacy): add field desensitization annotation with strategies` |
| D27 | 接口限流 | Redis 令牌桶限流注解（按患者ID/接口维度） | `feat(common): add redis token-bucket rate limiter` |
| D28 | Swagger 文档 | SpringDoc 配置、分组、鉴权说明、示例 | `docs: add openapi documentation with auth and examples` |

### 阶段 4：部署与收尾（D29–D33）

| Day | 任务 | 实现要点 | Commit 信息 |
|---|---|---|---|
| D29 | Docker 打包 | 多阶段 Dockerfile（maven build→JRE slim）、分层缓存优化 | `build: add multi-stage dockerfile with layered jar` |
| D30 | Compose 编排 | docker-compose：mysql/redis/app、健康检查、初始化脚本 | `build: add docker compose stack with health checks` |
| D31 | 镜像 CI | GitHub Actions：tag 触发构建镜像并推送 GHCR | `ci: add docker image build and publish workflow` |
| D32 | 集成测试 | Testcontainers：MySQL+Redis 真实环境跑通存储与锁链路 | `test: add testcontainers integration tests for memory layer` |
| D33 | 文档收尾 | README（架构图/快速开始/徽章）、部署手册 | `docs: complete readme with architecture and deployment guide` |

---

## 四、统一存储对接规范（与外部 Python 中间件字段级对齐，代码零依赖）

本服务不依赖任何外部项目代码，仅遵循同一份 `med_session.proto` 协议规范：

| 规范项 | 约定 |
|---|---|
| 消息字段 | `message_id(UUIDv7)` / `session_id` / `tenant_id` / `dept_id` / `patient_id` / `role` / `content` / `token_count` / `masked` / `created_at(epoch millis)` / `metadata` |
| Redis 键 | `med:chat:{tenant_id}:{dept_id}:{session_id}` |
| MySQL 分表 | `med_message_{crc32(session_id) % 16}`，16 张分表 |
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
git add src/main/java/com/med/qa/memory/           && git commit -m "feat(memory): implement mysql store with hash-based table sharding"
git add src/main/resources/db/ src/main/resources/mapper/ && git commit -m "feat(memory): add flyway sharded table ddl and mybatis mappers"
git add src/test/java/com/med/qa/memory/           && git commit -m "test(memory): add sharding router unit tests"
git add pom.xml .github/                           && git commit -m "build: add mybatis and flyway dependencies"   # 如有
# 3. 推送
git push origin main
```

分批顺序约定：`common/config → domain → memory → rag → service → security → audit → privacy → controller → resources → test → 构建/CI 文件`。禁止一天所有变更混在一个大 commit 里推送。

---

## 六、简历技术亮点（本项目）

1. 自定义 Spring AI `ChatMemory`：MySQL 16 分表 + Redis 缓存 cache-aside 双层存储，Protobuf 统一序列化，与异构 Python 存储中间件实现字段级数据互通
2. 自研轻量医疗 RAG 向量库：余弦/L2 相似度计算、最小堆 Top-K 检索、科室/患者 ID 元数据标签过滤、LRU 容量淘汰与快照持久化，零第三方向量库依赖
3. 实现 Redis 分布式会话锁（SETNX + Lua 原子释放 + 看门狗续期），保障并发问诊写入一致性
4. 落地医院级安全合规：患者-会话归属校验、科室注解权限、AOP 操作审计、`@Desensitize` 隐私字段脱敏注解、令牌桶限流
5. SSE 流式 LLM 问诊接口，支持断连清理与心跳保活
6. Testcontainers 集成测试 + JaCoCo 覆盖率 + GitHub Actions 自动构建 Docker 镜像推送 GHCR，生产级 DevOps 闭环
