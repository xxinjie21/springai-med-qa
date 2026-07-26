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
