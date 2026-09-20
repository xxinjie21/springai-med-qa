# springai-med-qa

<div align="center">

[![CI](https://github.com/xxinjie21/springai-med-qa/actions/workflows/ci.yml/badge.svg)](https://github.com/xxinjie21/springai-med-qa/actions/workflows/ci.yml)
[![Docker Publish](https://github.com/xxinjie21/springai-med-qa/actions/workflows/docker-publish.yml/badge.svg)](https://github.com/xxinjie21/springai-med-qa/actions/workflows/docker-publish.yml)
[![GHCR](https://img.shields.io/badge/ghcr.io-springai--med--qa-blue?logo=docker)](https://github.com/xxinjie21/springai-med-qa/pkgs/container/springai-med-qa)
[![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.5-brightgreen?logo=springboot)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0.0-6bd33a?logo=spring)](https://spring.io/projects/spring-ai)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)

A hospital-grade AI consultation backend built on **Spring Boot 3 + Spring AI**.

**English** ｜ [简体中文](./README.md)

</div>

> The "front-end business system" for medical AI question answering plus distributed conversation
> memory. The storage contract, field definitions and serialization protocol are strictly aligned
> with [`med-langchain-memory`](https://github.com/xxinjie21/med-langchain-memory) (the Python
> middleware), so the two repositories can read and migrate each other's data even though the code
> bases are completely independent and share zero dependencies.

---

## Table of contents

- [What this service is](#what-this-service-is)
- [Architecture](#architecture)
- [Tech stack and component choices](#tech-stack-and-component-choices)
- [Module layout](#module-layout)
- [Unified storage contract](#unified-storage-contract)
- [API surface](#api-surface)
- [Error codes](#error-codes)
- [Environment variables](#environment-variables)
- [Quick start](#quick-start)
- [Testing and coverage](#testing-and-coverage)
- [Alerting](#alerting)
- [CI/CD](#cicd)
- [Deployment](#deployment)
- [Roadmap progress](#roadmap-progress)
- [License](#license)

---

## What this service is

| Dimension | Description |
|---|---|
| Role | AI consultation backend (a working system for patients and clinicians) |
| Storage foundation | The shared medical conversation storage contract (Redis keys, 16 MySQL shards, Protobuf) |
| Capabilities | SSE streaming consultation, RAG over medical knowledge, patient ownership and department scoping, operation audit trail, privacy masking, rate limiting |
| Engineering principle | **Use mature commercial or mainstream open-source components** instead of rebuilding primitives |

---

## Architecture

```mermaid
flowchart TB
    subgraph Client["Callers"]
        WEB["Patient / clinician workbench<br/>Swagger UI"]
    end

    subgraph App["springai-med-qa (Spring Boot 3.4.5 / Java 17)"]
        FILTER["ApiKeyAuthFilter<br/>X-API-Key authentication"]
        INTERCEPTOR["DeptScopeInterceptor<br/>@RequireDept department scope"]
        RATELIMIT["RateLimitAspect<br/>Redisson RRateLimiter"]
        AUDIT["AuditAspect<br/>@MedAudit audit trail"]
        CTRL["Controller layer<br/>Chat / Session / RagAdmin"]
        SVC["Service layer<br/>ChatStreamService / MedChatSessionService"]
        MEM["Memory layer<br/>MedChatMemoryRepository (two-tier reads and writes)"]
        RAG["RAG layer<br/>MedDocumentService / MedRetrievalService<br/>MedRagAdvisorFactory"]
    end

    subgraph AI["Spring AI 1.0.0"]
        CC["ChatClient + QuestionAnswerAdvisor"]
        EM["EmbeddingModel<br/>OpenAI compatible"]
        VS["RedisVectorStore<br/>RediSearch HNSW / COSINE"]
        CM["MessageWindowChatMemory"]
    end

    subgraph Store["Storage"]
        REDIS[("Redis Stack<br/>med:chat:{tenant}:{dept}:{session}<br/>med:doc:* vector index")]
        MYSQL[("MySQL 8<br/>ShardingSphere-JDBC<br/>med_message_{0..15}<br/>med_session / med_audit_log")]
    end

    LLM["External LLM / embedding gateway<br/>OpenAI compatible API"]

    WEB --> FILTER --> INTERCEPTOR --> RATELIMIT --> AUDIT --> CTRL
    CTRL --> SVC
    SVC --> CC
    SVC --> MEM
    CC --> CM --> MEM
    CC --> RAG
    RAG --> VS
    RAG --> EM --> LLM
    CC -.streaming SSE.-> LLM
    MEM --> REDIS
    MEM --> MYSQL
    VS --> REDIS
```

**What happens on a request**

1. Authentication (`ApiKeyAuthFilter`) then department scope (`@RequireDept`) then rate limiting
   (`@RateLimit`) then auditing (`@MedAudit`): all four cross-cutting concerns are carried by
   framework mechanisms (a servlet filter, a handler interceptor, AOP) rather than hand-written code.
2. A consultation turn goes through `ChatClient` plus `QuestionAnswerAdvisor`; the context is
   supplied by `MessageWindowChatMemory`, whose short-term window is persisted through a custom
   `ChatMemoryRepository` into the two-tier store.
3. RAG performs vector similarity search with three metadata TAG filters
   (`tenant_id`, `dept_id`, `patient_id`) and **never parses or extracts entities from text**.
4. MySQL is the source of truth and Redis is a cache window: a failed cache read degrades to a miss
   (falling back to MySQL), while a failed cache write or delete is propagated.

---

## Tech stack and component choices

| Capability | Component | Notes |
|---|---|---|
| Base framework | Spring Boot 3.4.5 | Web, IoC, AOP, graceful shutdown |
| Model calls and streaming | Spring AI 1.0.0 | `ChatClient`, SSE streaming, `EmbeddingModel` |
| Vector store and RAG | Spring AI `RedisVectorStore` + `QuestionAnswerAdvisor` | HNSW with COSINE similarity, metadata TAG filtering |
| MySQL sharding | ShardingSphere-JDBC 5.5.2 | Only the `crc32(session_id) % 16` algorithm plugin is ours; routing belongs to the framework |
| Distributed lock and rate limiting | Redisson 3.45.1 (`RLock` / `RRateLimiter`) | Session lock with watchdog renewal, annotation-driven rate limiting |
| ORM and migrations | MyBatis 3.0.4 + Flyway | Data access and versioned DDL (V1 to V3) |
| Serialization | Protobuf 4.29.3 | Cross-language conversation protocol, stored as binary |
| Masking | Hutool `DesensitizedUtil` 5.8.37 | ID card, phone number and medical record number masking triggered by a Jackson annotation |
| API documentation | SpringDoc OpenAPI 2.8.5 | Swagger UI with chat, session and rag groups |
| Health checks | Spring Boot Actuator | `/actuator/health` with per-component MySQL and Redis probes, `/actuator/info` version matrix, Kubernetes liveness and readiness probe groups |
| Metrics and alerting | Micrometer, Prometheus, Alertmanager | `/actuator/prometheus` scrape endpoint; `com.med.qa.alert` pushes alerts to the log and to `med_qa_alert_total`, with rules and routing under `deploy/` |
| Testing | JUnit 5, Mockito, H2, Testcontainers | The unit suite is fully offline; integration tests skip themselves when Docker is unavailable |
| Coverage | JaCoCo 0.8.13 | Bound to `verify`; the report is a CI artifact and the `check` gate fails the build below the thresholds |

---

## Module layout

```
src/main/java/com/med/qa/
├── common/            # ApiResult/PageResult, ErrorCode/BizException, global exception handling
│   └── ratelimit/     # @RateLimit annotation plus the Redisson RRateLimiter aspect
├── config/            # Redis, Redisson, VectorStore, Embedding, ChatMemory, Security, OpenAPI wiring
├── domain/            # ChatMessageDO/ChatSessionDO/AuditLogDO plus RoleType/SessionStatus/AuditOutcome
├── memory/            # Official ChatMemory repository bridge, two-tier reads/writes, cache, lock, shard algorithm, Protobuf codec
├── rag/               # Document ingestion, tag-filtered retrieval, QuestionAnswerAdvisor factory
├── mapper/            # MyBatis mappers over the ShardingSphere data source plus type handlers
├── service/           # ChatStreamService / MedChatSessionService / AuditService
├── security/          # ApiKeyAuthFilter, PatientAccessGuard, @RequireDept department authorization
├── audit/             # @MedAudit annotation, AOP aspect, audit persistence
├── privacy/           # @Desensitize annotation, Jackson serializer, MaskType
├── actuator/          # MedStorageHealthIndicator plus MedComponentInfoContributor
├── alert/             # Alert chain: storage monitor, policy and deduplication, log and Micrometer sinks
├── controller/        # REST and SSE endpoints plus DTOs
└── MedQaApplication.java

src/main/resources/
├── application.yml             # Shared configuration with every environment placeholder
├── application-dev.yml / -prod.yml
├── sharding/med-sharding.yaml  # ShardingSphere rules (SHARDING plus SINGLE)
├── mapper/*.xml                # MyBatis SQL
├── db/migration/               # Flyway V1 to V3 DDL
└── META-INF/services/...       # Shard algorithm SPI registration

docs/                            # Deployment handbook
docker/mysql/init/               # Compose MySQL init script (database plus least-privilege account)
deploy/prometheus/               # Prometheus scrape config and alert rules
deploy/alertmanager/             # Alertmanager routing and inhibition rules
scripts/verify-docker-build.sh   # Real container build verification
```

---

## Unified storage contract

| Item | Rule |
|---|---|
| Redis key | `med:chat:{tenant}:{dept}:{session}` |
| MySQL shard | `med_message_{crc32(session_id) % 16}` (16 physical tables) |
| Message fields | `message_id(UUIDv7)`, `session_id`, `tenant_id`, `dept_id`, `patient_id`, `role`, `content`, `token_count`, `masked`, `created_at(epoch millis)`, `metadata` |
| Role enum | `PATIENT=0` / `DOCTOR=1` / `ASSISTANT=2` / `SYSTEM=3` |
| Serialization | Protobuf (`med_session.proto`, the cross-language protocol) in storage; JSON for API DTOs |

Both systems therefore write interoperable data: identical fields, keys, shard routing and encoding.

---

## API surface

Every endpoint requires the `X-API-Key` header (configurable through `MED_SECURITY_HEADER`).
Swagger UI: `http://localhost:8080/swagger-ui.html`. OpenAPI document: `/v3/api-docs`.

| Method | Path | Purpose | Notes |
|---|---|---|---|
| `POST` | `/api/chat/stream` | SSE streaming consultation (`text/event-stream`) | `@RateLimit` plus department-scoped RAG |
| `POST` | `/api/sessions` | Create a consultation session | `@RateLimit` |
| `GET` | `/api/sessions/{sessionId}` | Read one session | Patients may only read their own |
| `POST` | `/api/sessions/{sessionId}/close` | Close a session (idempotent) | |
| `POST` | `/api/sessions/{sessionId}/archive` | Archive a session (idempotent) | An archived session can no longer be closed |
| `GET` | `/api/sessions` | Paged session listing | `tenantId`, `deptId`, `patientId`, `page`, `size` |
| `POST` | `/api/rag/documents/ingest` | Batch ingestion of medical documents | `@RateLimit`, staff only |
| `POST` | `/api/rag/documents/delete` | Delete by id list or by isolation scope | |
| `POST` | `/api/rag/documents/search` | Tag-scoped retrieval preview | Passes through `topK`, `threshold`, `includeShared` |
| `GET` | `/actuator/health` | Health check | Container and orchestrator probes |
| `GET` | `/actuator/prometheus` | Prometheus metrics | Scrape endpoint of the monitoring stack |

Unified response envelope:

```json
{ "code": 0, "message": "success", "data": { } }
```

Department-level documents use the reserved `patient_id` value `__shared__`; a patient query filters
with `patient_id IN [patient, __shared__]`, while tenant and department are equality conditions
combined with AND.

---

## Error codes

| Code | Meaning | Typical situation |
|---|---|---|
| `0` | Success | |
| `40000` | Validation failed | Missing identity triple, `topK` out of range |
| `40100` | Unauthenticated | Missing or invalid `X-API-Key` |
| `40300` | Forbidden | Patient reading another patient's session, cross-department access |
| `40400` | Not found | Unknown session |
| `40500` | Method not allowed | |
| `40900` | Session lock held | Concurrent writes to one session, retry shortly |
| `42900` | Rate limited | Redisson `RRateLimiter` bucket exhausted |
| `50000` | Internal error | |
| `50201` | LLM or embedding service unavailable | Model gateway timeout or error |
| `50301` | Storage unavailable | MySQL or Redis failure |

> Failure semantics are layered: `IllegalArgumentException` means a programming error by the caller,
> while `BizException` (carrying an `ErrorCode`) means a business error. A cache read degrades to a
> miss because MySQL is the source of truth; the lock and the rate limiter never degrade silently.

---

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` | Active profile (`dev` or `prod`) |
| `SERVER_PORT` | `8080` | HTTP port |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DATABASE` | `localhost` / `6379` / `0` | Shared by cache, lock, rate limiter and vector index |
| `MED_MYSQL_HOST` / `MED_MYSQL_PORT` / `MED_MYSQL_DATABASE` | `127.0.0.1` / `3306` / `med_qa` | ShardingSphere data source |
| `MED_MYSQL_USERNAME` / `MED_MYSQL_PASSWORD` | `med_qa` / `med_qa` | Application account |
| `OPENAI_BASE_URL` / `OPENAI_API_KEY` | `https://api.openai.com` / empty | Any OpenAI-compatible gateway |
| `MED_EMBEDDING_MODEL` / `MED_EMBEDDING_DIMENSIONS` | `text-embedding-3-small` / `1536` | Must match the index vector width |
| `MED_SECURITY_ENABLED` / `MED_SECURITY_REQUIRE_AUTH` | `true` / `true` | API key authentication switches |
| `MED_SECURITY_DEPT_SCOPE_ENABLED` | `true` | Department annotation authorization switch |
| `MED_SECURITY_HEADER` | `X-API-Key` | Authentication header |
| `MED_RATE_LIMIT_ENABLED` | `true` | Rate limiting switch (set to `false` for offline tests) |
| `MED_AUDIT_ENABLED` | `true` | Audit persistence switch |
| `MED_CACHE_TTL` / `MED_CACHE_MAX_MESSAGES` | `30m` / `200` | Conversation cache window |
| `MED_CHAT_MAX_MESSAGES` | `20` | Short-term memory window |
| `MED_CHAT_STREAM_HEARTBEAT` / `MED_CHAT_STREAM_TIMEOUT` | `15` / `120` | SSE heartbeat and timeout in seconds |
| `MED_LOCK_WAIT_TIME` / `MED_LOCK_LEASE_TIME` / `MED_LOCK_WATCHDOG_TIMEOUT` | `3s` / `0s` / `30s` | Session lock (a lease of `0` hands renewal to the watchdog) |
| `MED_RAG_INDEX_NAME` / `MED_RAG_KEY_PREFIX` | `med-doc-index` / `med:doc:` | Vector index |
| `MED_RAG_TOP_K` / `MED_RAG_MAX_TOP_K` / `MED_RAG_SIMILARITY_THRESHOLD` | `4` / `50` / `0.0` | Retrieval parameters |
| `MED_RAG_INGEST_BATCH_SIZE` / `MED_RAG_INGEST_MAX_DOCUMENTS` | `25` / `500` | Ingestion limits |
| `MED_ALERT_ENABLED` | `true` | Master switch of the alert chain (no alert bean exists when `false`) |
| `MED_ALERT_CHECK_INTERVAL` / `MED_ALERT_INITIAL_DELAY` | `60s` / `30s` | Storage probe period and startup grace period |
| `MED_ALERT_COOLDOWN` | `10m` | Suppression window per `code:component` fingerprint (`0` disables deduplication) |
| `MED_ALERT_MINIMUM_SEVERITY` | `INFO` | Severity floor (raise to `WARNING` to silence recovery notices) |
| `MED_ALERT_KEY_PREFIX` | `med:alert:` | Redis namespace of the deduplication map |

The full operational reference lives in the [deployment handbook](./docs/DEPLOYMENT.md).

---

## Quick start

### Option 1: the whole stack with Docker Compose (recommended)

```bash
# One command brings up mysql, redis-stack and the application, which starts only after both
# dependencies report healthy.
docker compose up -d

# Follow the startup log
docker compose logs -f app

# Verify
curl http://localhost:8080/actuator/health
open http://localhost:8080/swagger-ui.html
```

> Compose disables authentication and rate limiting by default (`MED_SECURITY_ENABLED=false` and
> friends) so the stack runs out of the box. For production, follow the deployment handbook.

### Option 2: local Maven development

```bash
# The bundled Maven Wrapper means no Maven installation is required; JDK 17 or newer.
./mvnw clean verify          # compile, full unit suite, JaCoCo coverage

# Start only Redis Stack (cache and vector index) and use a local MySQL
docker compose up -d redis-stack

# Run the application
./mvnw spring-boot:run
```

> The unit suite uses H2 and Mockito doubles, so it is green without any middleware; Testcontainers
> integration tests skip themselves when no Docker daemon is running.

### First requests (optional)

```bash
# 1) Create a session
curl -X POST http://localhost:8080/api/sessions \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"t-1001","deptId":"dept-cardio","patientId":"pat-7731","title":"Hypertension follow-up"}'

# 2) Streaming consultation over SSE
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"t-1001","dept":"dept-cardio","session":"sess-9f2c1a","patientId":"pat-7731","message":"Can I take ibuprofen while on an ACE inhibitor?"}'
```

---

## Testing and coverage

| Item | Description |
|---|---|
| Unit tests | JUnit 5 and Mockito; every external dependency is mocked or replaced by H2, so `mvn test` is offline and green |
| Build contract tests | Parse `pom.xml`, `Dockerfile`, `docker-compose.yml` and `.github/workflows/*.yml` and assert the key contracts |
| Integration tests | `src/test/java/com/med/qa/integration`: Testcontainers boots a real MySQL and Redis Stack to exercise the storage and lock chain; disabled automatically without Docker |
| Coverage | JaCoCo is bound to `verify`; the report lands in `target/site/jacoco/index.html` |
| Coverage gate | The `jacoco-coverage-gate` execution (bound to `verify`, `haltOnFailure`) enforces instructions at 90 percent or more, branches at 80 percent or more and lines at 90 percent or more, with the floors declared as `jacoco.min.*` properties; falling below fails the build so CI cannot merge it |

```bash
./mvnw clean verify          # full unit suite, coverage report and the gate
./mvnw clean test            # unit tests only, without the gate
```

The build contracts guarded by the gate are themselves covered:
`com.med.qa.ci.CoverageGateConfigTest` parses `pom.xml` with DOM and asserts that the gate exists,
is bound to `verify`, and takes all three counter floors from properties within `[0,1]`.

---

## Alerting

Actuator only answers when somebody asks, which is not good enough for a hospital backend. The
`com.med.qa.alert` package adds the push side:

```
MedStorageAlertMonitor  (scheduled poll of the existing MedStorageHealthIndicator)
        -> MedAlertNotifier  (policy filtering plus Redisson TTL-map deduplication)
                -> LoggingMedAlertSink   writes a structured key=value line to the application log
                -> MetricsMedAlertSink   increments med_qa_alert_total (Micrometer)
```

- **Policy** lives in `med.alert.*`: master switch, polling interval, suppression window, severity
  floor and muted codes, all overridable through the environment.
- **Deduplication** keys on the `code:component` fingerprint in `med:alert:dedupe`, a Redisson
  `RMapCache` whose TTL equals the cooldown, so every replica shares one suppression window and a
  single outage pages once instead of once per pod.
- **Fail-open**: if the deduplication store is unreachable the alert is dispatched anyway, because an
  unreachable Redis is itself one of the conditions this chain reports.
- **Recovery notices**: a component that answers again raises an `INFO` alert, so an engineer joining
  mid-incident can tell "still broken" from "fixed twenty minutes ago".

An optional monitoring stack sits behind the `observability` Compose profile and is not started by a
plain `docker compose up -d`:

```bash
docker compose --profile observability up -d
# Prometheus UI:   http://localhost:9090
# Alertmanager UI: http://localhost:9093
```

| File | Purpose |
|---|---|
| `deploy/prometheus/prometheus.yml` | Scrapes `app:8080/actuator/prometheus` every 15 seconds, loads the rules and points at Alertmanager |
| `deploy/prometheus/med-qa-alerts.yml` | `MedQaTargetDown`, `MedQaStorageUnavailable`, `MedQaStorageProbeFailed`, `MedQaAlertStorm`, `MedQaHighServerErrorRate`, `MedQaConsultationLatencyHigh`, `MedQaRateLimitStorm` |
| `deploy/alertmanager/alertmanager.yml` | Groups by `alertname` and `component`, routes `critical` to a dedicated receiver with a shorter repeat interval, and inhibits the symptoms of a wider outage |

> The `webhook_configs.url` values in the Alertmanager config are placeholders: Alertmanager does not
> expand environment variables inside its configuration file, so the real relay endpoint has to be
> substituted at deploy time. Until then alerts remain visible in the Alertmanager UI but are not
> pushed to a phone.

Custom meters: `med_qa_alert_total{severity,code,component}` (counter) and
`med_qa_alert_last_epoch_seconds` (timestamp of the most recent alert, usable for a dead-man's-switch
rule).

---

## CI/CD

| Workflow | Trigger | Action |
|---|---|---|
| `ci.yml` | Push or pull request to `main` | Temurin JDK 17 with Maven caching, then `./mvnw verify`, then uploads the JaCoCo and Surefire reports |
| `docker-publish.yml` | Push of a `v*` tag or manual `workflow_dispatch` | Multi-stage build inside the container, login to GHCR, push `ghcr.io/xxinjie21/springai-med-qa` |

---

## Deployment

The complete handbook (image acquisition, the full environment variable table, health checks and
alerting, troubleshooting, backup and rollback, security hardening) is
**[`docs/DEPLOYMENT.md`](./docs/DEPLOYMENT.md)**.

**Container build verification**: `scripts/verify-docker-build.sh` runs a real `docker build` and
then asserts the OCI labels, the non-root user, the layered layout and the launcher entrypoint. It
exits cleanly with code 2 when no Docker daemon is available, so it never blocks a pipeline.

---

## Roadmap progress

Delivery follows the five phases in [`ROADMAP.md`](./ROADMAP.md), one iteration per day, each closing
the loop of code, unit tests, commit and push:

| Phase | Iterations | Status |
|---|---|---|
| Phase 0, engineering foundation | D1 to D5 | Done |
| Phase 1, conversation memory | D6 to D12 | Done |
| Phase 2, RAG retrieval | D13 to D18 | Done |
| Phase 3, business capabilities | D19 to D26 | Done |
| Phase 4, deployment and wrap-up | D27 to D31 | Done |
| Phase 5, operations hardening | D32 to D33 | Done |

---

## License

[MIT](./LICENSE)
