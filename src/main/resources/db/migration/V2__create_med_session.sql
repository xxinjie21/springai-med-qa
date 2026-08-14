-- Flyway migration: create the med_session table holding one row per consultation session.
--
-- Unlike med_message, sessions are NOT sharded: a session row is tiny, is read by primary key
-- (session_id) and is listed per patient/department, so a single table keeps the paged listing a
-- plain indexed query instead of a 16-way scatter-gather. ShardingSphere-JDBC serves it through its
-- SINGLE rule (`tables: - "*.*"` in sharding/med-sharding.yaml), which routes any non-sharded table
-- to the only configured data source.
--
-- Column layout mirrors the ChatSession message of med_session.proto and therefore the unified
-- medical storage specification (ROADMAP section 4):
--   session_id / tenant_id / dept_id / patient_id / title /
--   status(numeric spec code: ACTIVE=0, CLOSED=1, ARCHIVED=2) /
--   created_at(epoch millis) / updated_at(epoch millis)
--
-- The DDL is intentionally backend-agnostic: it runs unchanged against MySQL (production) and
-- against an in-memory H2 schema in MySQL compatibility mode (tests). Index names carry the table
-- name because H2 requires schema-wide unique index names.

CREATE TABLE IF NOT EXISTS med_session (
    session_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    dept_id      VARCHAR(64)  NOT NULL,
    patient_id   VARCHAR(64)  NOT NULL,
    title        VARCHAR(255),
    status       TINYINT      NOT NULL,
    created_at   BIGINT       NOT NULL,
    updated_at   BIGINT       NOT NULL,
    PRIMARY KEY (session_id),
    INDEX idx_med_session_patient (tenant_id, dept_id, patient_id, created_at),
    INDEX idx_med_session_status (tenant_id, dept_id, status, created_at)
);
