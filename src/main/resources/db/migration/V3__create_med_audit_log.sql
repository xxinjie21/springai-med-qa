-- Flyway migration: create the med_audit_log table holding one row per audited medical operation.
--
-- The trail answers the five questions of a hospital audit review:
--   who      -> operator_id / operator_role inside tenant_id / dept_id
--   what     -> action (stable screaming snake case code, e.g. SESSION_CLOSE)
--   on what  -> resource_type / resource_id
--   result   -> outcome (SUCCESS=0 / FAILURE=1) + error_code (business code of a failure)
--   cost     -> latency_millis (wall-clock duration of the audited call)
-- created_at is epoch milliseconds, like every other timestamp of the unified storage specification
-- (ROADMAP section 4).
--
-- The table is intentionally free of clinical payload: no question text, no model answer, no
-- retrieved document content. Duplicating protected health information into a second store with its
-- own retention rules is a liability, and the trail does not need it to be useful.
--
-- Like med_session, the table is NOT sharded: reviews filter by tenant/department/operator/action
-- over a time window, which the composite indexes below turn into a range scan on one table instead
-- of a 16-way scatter-gather. ShardingSphere-JDBC serves it through its SINGLE rule
-- (sharding/med-sharding.yaml).
--
-- The DDL is backend-agnostic: it runs unchanged against MySQL (production) and against an in-memory
-- H2 schema in MySQL compatibility mode (tests). Index names carry the table name because H2
-- requires schema-wide unique index names.

CREATE TABLE IF NOT EXISTS med_audit_log (
    audit_id       VARCHAR(64)  NOT NULL,
    tenant_id      VARCHAR(64)  NOT NULL,
    dept_id        VARCHAR(64)  NOT NULL,
    operator_id    VARCHAR(64)  NOT NULL,
    operator_role  VARCHAR(16),
    action         VARCHAR(64)  NOT NULL,
    resource_type  VARCHAR(32),
    resource_id    VARCHAR(128),
    outcome        TINYINT      NOT NULL,
    error_code     INT,
    latency_millis BIGINT       NOT NULL,
    message        VARCHAR(500),
    created_at     BIGINT       NOT NULL,
    PRIMARY KEY (audit_id),
    INDEX idx_med_audit_log_scope (tenant_id, dept_id, created_at),
    INDEX idx_med_audit_log_operator (tenant_id, dept_id, operator_id, created_at),
    INDEX idx_med_audit_log_action (tenant_id, dept_id, action, created_at)
);
