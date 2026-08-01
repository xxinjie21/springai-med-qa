# springai-med-qa

基于 **Spring Boot 3 + Spring AI** 的医院生产级 AI 问诊后端服务。

> 医疗 AI 知识问答 + 分布式会话记忆存储的「前端业务系统」。会话存储规范、字段定义、序列化协议与 [`med-langchain-memory`](https://github.com/xxinjie21/med-langchain-memory)（Python 底层中间件）严格对齐，两仓库数据可互通，但代码完全独立。

---

## 核心定位

| 维度 | 说明 |
|---|---|
| 角色 | AI 问诊问答后端（面向患者 / 医生的工作系统） |
| 存储底座 | 复用同一套医疗会话存储规范（Redis 键、MySQL 16 分表、Protobuf） |
| 能力 | 流式问诊、RAG 医疗知识检索、权限校验、操作审计、隐私脱敏 |
| 技术路线 | **全面使用成熟商业化 / 主流开源组件**，不重复造底层轮子 |

---

## 技术栈

| 能力 | 组件 | 说明 |
|---|---|---|
| 基础框架 | Spring Boot 3.4.x | Web / IOC / 事务 |
| AI 调用与流式输出 | Spring AI | ChatClient、SSE 流式、EmbeddingModel |
| 向量存储与 RAG | Spring AI `RedisVectorStore` + `QuestionAnswerAdvisor` | 余弦相似度检索 + 元数据标签过滤 |
| MySQL 分表 | ShardingSphere-JDBC | 仅自写 `crc32(session_id) % 16` 分片算法插件，路由交给框架 |
| 分布式锁 / 限流 | Redisson（`RLock` / `RRateLimiter`） | 会话并发锁、接口限流 |
| ORM | MyBatis + Flyway | 数据访问与版本化建表 |
| 脱敏 | Hutool `DesensitizedUtil` | 身份证 / 手机号 / 病历号字段掩码 |
| 接口文档 | springdoc-openapi | Swagger UI |
| 测试 | JUnit 5 + Mockito + H2 | 单测不依赖真实中间件 |
| 覆盖率 | JaCoCo | 绑定 `verify` 阶段，报告上传为 CI 产物 |

---

## 模块结构

```
src/main/java/com/med/qa/
├── common/            # 通用：统一响应 ApiResult、业务异常 BizException/ErrorCode、全局异常处理
├── domain/            # 领域实体：ChatMessageDO、ChatSessionDO、RoleType/SessionStatus 枚举
├── config/            # Spring 配置类
├── memory/            # 自定义 ChatMemory 仓储 + ShardingSphere 分片算法
│   └── sharding/      # Crc32ShardingAlgorithm（crc32(session_id) % 16）
├── rag/               # Spring AI 向量检索与 RAG Advisor 装配
├── security/          # 患者会话权限校验、Redisson 分布式锁 / 限流
├── privacy/           # Hutool 脱敏注解与切面
├── audit/             # 医疗操作日志审计
├── service/           # 业务编排
├── controller/        # REST / SSE 接口层
└── MedQaApplication.java

src/main/resources/
├── application.yml            # 公共配置
├── application-dev.yml       # 开发环境
├── application-prod.yml      # 生产环境
├── sharding/med-sharding.yaml# ShardingSphere 分片规则
└── META-INF/services/...     # 分片算法 SPI 注册
```

---

## 统一存储对接规范（与 med-langchain-memory 互通）

为保证异构系统数据互通，两仓库严格遵循同一套规范：

| 项 | 规则 |
|---|---|
| Redis 键 | `med:chat:{tenant}:{dept}:{session}` |
| MySQL 分表 | `med_message_{crc32(session_id) % 16}`（共 16 张表） |
| 消息字段 | `session_id` / `tenant` / `dept` / `patient_id` / `role` / `content` / `created_at`（epoch millis，UUIDv7 主键） |
| 序列化 | Protobuf（`med_session.proto`，跨语言统一协议） |

---

## 本地开发

```bash
# 使用 Maven Wrapper（已内置，无需本地预装 Maven）
./mvnw clean verify        # 编译 + 全量单测 + JaCoCo 覆盖率

# 本地启动（需配置 application-dev.yml 中的 Redis / MySQL / LLM 连接）
./mvnw spring-boot:run
```

> 单测使用 H2 内存库与 Mockito 替身，无需启动任何中间件即可全绿。

---

## CI / CD

GitHub Actions（` .github/workflows/ci.yml`）在每次 push / PR 到 `main` 时自动：

1. `actions/checkout@v4` 拉取代码
2. `actions/setup-java@v4` 配置 Temurin JDK 17（开启 Maven 缓存）
3. `chmod +x ./mvnw` 确保包装器可执行
4. `./mvnw verify` 执行全量单测 + 生成 JaCoCo 覆盖率
5. 上传 `target/site/jacoco` 覆盖率报告与 `target/surefire-reports` 测试报告为构建产物

---

## 每日迭代节奏

项目按 `ROADMAP.md` 的分阶段任务表，由每日自动化任务完成「编码 → 单测 → 分模块 commit → 推送 GitHub」闭环，每个迭代点 30–60 分钟可独立提交。

---

## License

MIT
