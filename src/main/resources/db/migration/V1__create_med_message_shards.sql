-- Flyway migration: create the 16 physical shards of the med_message logical table.
--
-- ShardingSphere-JDBC routes med_message writes/reads to med_message_{crc32(session_id) % 16};
-- the actualDataNodes med_ds.med_message_{0..15} declared in sharding/med-sharding.yaml make
-- these 16 physical tables the only storage the rule knows about.
--
-- Column layout is field-level aligned with the unified medical storage specification
-- (ROADMAP section 4):
--   message_id(UUIDv7) / session_id(shard key) / tenant_id / dept_id / patient_id /
--   role(numeric spec code) / content / token_count / masked / created_at(epoch millis) / metadata
--
-- The DDL is intentionally backend-agnostic: it runs unchanged against MySQL (production, via
-- ShardingSphere) and against an in-memory H2 schema in MySQL compatibility mode (tests).
-- Index names are suffixed with the shard number because H2 requires schema-wide unique index names,
-- whereas MySQL scopes index names per table; the unique suffix keeps the script portable.

CREATE TABLE IF NOT EXISTS med_message_0 (
    message_id   VARCHAR(64)  NOT NULL,
    session_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    dept_id      VARCHAR(64)  NOT NULL,
    patient_id   VARCHAR(64)  NOT NULL,
    role         TINYINT      NOT NULL,
    content      LONGTEXT     NOT NULL,
    token_count  INT          NOT NULL,
    masked       BOOLEAN      NOT NULL,
    created_at   BIGINT       NOT NULL,
    metadata     LONGTEXT,
    PRIMARY KEY (message_id),
    INDEX idx_med_message_0_session (session_id),
    INDEX idx_med_message_0_session_created (session_id, created_at)
);

CREATE TABLE IF NOT EXISTS med_message_1 (
    message_id   VARCHAR(64)  NOT NULL,
    session_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    dept_id      VARCHAR(64)  NOT NULL,
    patient_id   VARCHAR(64)  NOT NULL,
    role         TINYINT      NOT NULL,
    content      LONGTEXT     NOT NULL,
    token_count  INT          NOT NULL,
    masked       BOOLEAN      NOT NULL,
    created_at   BIGINT       NOT NULL,
    metadata     LONGTEXT,
    PRIMARY KEY (message_id),
    INDEX idx_med_message_1_session (session_id),
    INDEX idx_med_message_1_session_created (session_id, created_at)
);

CREATE TABLE IF NOT EXISTS med_message_2 (
    message_id   VARCHAR(64)  NOT NULL,
    session_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    dept_id      VARCHAR(64)  NOT NULL,
    patient_id   VARCHAR(64)  NOT NULL,
    role         TINYINT      NOT NULL,
    content      LONGTEXT     NOT NULL,
    token_count  INT          NOT NULL,
    masked       BOOLEAN      NOT NULL,
    created_at   BIGINT       NOT NULL,
    metadata     LONGTEXT,
    PRIMARY KEY (message_id),
    INDEX idx_med_message_2_session (session_id),
    INDEX idx_med_message_2_session_created (session_id, created_at)
);

CREATE TABLE IF NOT EXISTS med_message_3 (
    message_id   VARCHAR(64)  NOT NULL,
    session_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    dept_id      VARCHAR(64)  NOT NULL,
    patient_id   VARCHAR(64)  NOT NULL,
    role         TINYINT      NOT NULL,
    content      LONGTEXT     NOT NULL,
    token_count  INT          NOT NULL,
    masked       BOOLEAN      NOT NULL,
    created_at   BIGINT       NOT NULL,
    metadata     LONGTEXT,
    PRIMARY KEY (message_id),
    INDEX idx_med_message_3_session (session_id),
    INDEX idx_med_message_3_session_created (session_id, created_at)
);

CREATE TABLE IF NOT EXISTS med_message_4 (
    message_id   VARCHAR(64)  NOT NULL,
    session_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    dept_id      VARCHAR(64)  NOT NULL,
    patient_id   VARCHAR(64)  NOT NULL,
    role         TINYINT      NOT NULL,
    content      LONGTEXT     NOT NULL,
    token_count  INT          NOT NULL,
    masked       BOOLEAN      NOT NULL,
    created_at   BIGINT       NOT NULL,
    metadata     LONGTEXT,
    PRIMARY KEY (message_id),
    INDEX idx_med_message_4_session (session_id),
    INDEX idx_med_message_4_session_created (session_id, created_at)
);

CREATE TABLE IF NOT EXISTS med_message_5 (
    message_id   VARCHAR(64)  NOT NULL,
    session_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    dept_id      VARCHAR(64)  NOT NULL,
    patient_id   VARCHAR(64)  NOT NULL,
    role         TINYINT      NOT NULL,
    content      LONGTEXT     NOT NULL,
    token_count  INT          NOT NULL,
    masked       BOOLEAN      NOT NULL,
    created_at   BIGINT       NOT NULL,
    metadata     LONGTEXT,
    PRIMARY KEY (message_id),
    INDEX idx_med_message_5_session (session_id),
    INDEX idx_med_message_5_session_created (session_id, created_at)
);

CREATE TABLE IF NOT EXISTS med_message_6 (
    message_id   VARCHAR(64)  NOT NULL,
    session_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    dept_id      VARCHAR(64)  NOT NULL,
    patient_id   VARCHAR(64)  NOT NULL,
    role         TINYINT      NOT NULL,
    content      LONGTEXT     NOT NULL,
    token_count  INT          NOT NULL,
    masked       BOOLEAN      NOT NULL,
    created_at   BIGINT       NOT NULL,
    metadata     LONGTEXT,
    PRIMARY KEY (message_id),
    INDEX idx_med_message_6_session (session_id),
    INDEX idx_med_message_6_session_created (session_id, created_at)
);

CREATE TABLE IF NOT EXISTS med_message_7 (
    message_id   VARCHAR(64)  NOT NULL,
    session_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    dept_id      VARCHAR(64)  NOT NULL,
    patient_id   VARCHAR(64)  NOT NULL,
    role         TINYINT      NOT NULL,
    content      LONGTEXT     NOT NULL,
    token_count  INT          NOT NULL,
    masked       BOOLEAN      NOT NULL,
    created_at   BIGINT       NOT NULL,
    metadata     LONGTEXT,
    PRIMARY KEY (message_id),
    INDEX idx_med_message_7_session (session_id),
    INDEX idx_med_message_7_session_created (session_id, created_at)
);

CREATE TABLE IF NOT EXISTS med_message_8 (
    message_id   VARCHAR(64)  NOT NULL,
    session_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    dept_id      VARCHAR(64)  NOT NULL,
    patient_id   VARCHAR(64)  NOT NULL,
    role         TINYINT      NOT NULL,
    content      LONGTEXT     NOT NULL,
    token_count  INT          NOT NULL,
    masked       BOOLEAN      NOT NULL,
    created_at   BIGINT       NOT NULL,
    metadata     LONGTEXT,
    PRIMARY KEY (message_id),
    INDEX idx_med_message_8_session (session_id),
    INDEX idx_med_message_8_session_created (session_id, created_at)
);

CREATE TABLE IF NOT EXISTS med_message_9 (
    message_id   VARCHAR(64)  NOT NULL,
    session_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    dept_id      VARCHAR(64)  NOT NULL,
    patient_id   VARCHAR(64)  NOT NULL,
    role         TINYINT      NOT NULL,
    content      LONGTEXT     NOT NULL,
    token_count  INT          NOT NULL,
    masked       BOOLEAN      NOT NULL,
    created_at   BIGINT       NOT NULL,
    metadata     LONGTEXT,
    PRIMARY KEY (message_id),
    INDEX idx_med_message_9_session (session_id),
    INDEX idx_med_message_9_session_created (session_id, created_at)
);

CREATE TABLE IF NOT EXISTS med_message_10 (
    message_id   VARCHAR(64)  NOT NULL,
    session_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    dept_id      VARCHAR(64)  NOT NULL,
    patient_id   VARCHAR(64)  NOT NULL,
    role         TINYINT      NOT NULL,
    content      LONGTEXT     NOT NULL,
    token_count  INT          NOT NULL,
    masked       BOOLEAN      NOT NULL,
    created_at   BIGINT       NOT NULL,
    metadata     LONGTEXT,
    PRIMARY KEY (message_id),
    INDEX idx_med_message_10_session (session_id),
    INDEX idx_med_message_10_session_created (session_id, created_at)
);

CREATE TABLE IF NOT EXISTS med_message_11 (
    message_id   VARCHAR(64)  NOT NULL,
    session_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    dept_id      VARCHAR(64)  NOT NULL,
    patient_id   VARCHAR(64)  NOT NULL,
    role         TINYINT      NOT NULL,
    content      LONGTEXT     NOT NULL,
    token_count  INT          NOT NULL,
    masked       BOOLEAN      NOT NULL,
    created_at   BIGINT       NOT NULL,
    metadata     LONGTEXT,
    PRIMARY KEY (message_id),
    INDEX idx_med_message_11_session (session_id),
    INDEX idx_med_message_11_session_created (session_id, created_at)
);

CREATE TABLE IF NOT EXISTS med_message_12 (
    message_id   VARCHAR(64)  NOT NULL,
    session_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    dept_id      VARCHAR(64)  NOT NULL,
    patient_id   VARCHAR(64)  NOT NULL,
    role         TINYINT      NOT NULL,
    content      LONGTEXT     NOT NULL,
    token_count  INT          NOT NULL,
    masked       BOOLEAN      NOT NULL,
    created_at   BIGINT       NOT NULL,
    metadata     LONGTEXT,
    PRIMARY KEY (message_id),
    INDEX idx_med_message_12_session (session_id),
    INDEX idx_med_message_12_session_created (session_id, created_at)
);

CREATE TABLE IF NOT EXISTS med_message_13 (
    message_id   VARCHAR(64)  NOT NULL,
    session_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    dept_id      VARCHAR(64)  NOT NULL,
    patient_id   VARCHAR(64)  NOT NULL,
    role         TINYINT      NOT NULL,
    content      LONGTEXT     NOT NULL,
    token_count  INT          NOT NULL,
    masked       BOOLEAN      NOT NULL,
    created_at   BIGINT       NOT NULL,
    metadata     LONGTEXT,
    PRIMARY KEY (message_id),
    INDEX idx_med_message_13_session (session_id),
    INDEX idx_med_message_13_session_created (session_id, created_at)
);

CREATE TABLE IF NOT EXISTS med_message_14 (
    message_id   VARCHAR(64)  NOT NULL,
    session_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    dept_id      VARCHAR(64)  NOT NULL,
    patient_id   VARCHAR(64)  NOT NULL,
    role         TINYINT      NOT NULL,
    content      LONGTEXT     NOT NULL,
    token_count  INT          NOT NULL,
    masked       BOOLEAN      NOT NULL,
    created_at   BIGINT       NOT NULL,
    metadata     LONGTEXT,
    PRIMARY KEY (message_id),
    INDEX idx_med_message_14_session (session_id),
    INDEX idx_med_message_14_session_created (session_id, created_at)
);

CREATE TABLE IF NOT EXISTS med_message_15 (
    message_id   VARCHAR(64)  NOT NULL,
    session_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    dept_id      VARCHAR(64)  NOT NULL,
    patient_id   VARCHAR(64)  NOT NULL,
    role         TINYINT      NOT NULL,
    content      LONGTEXT     NOT NULL,
    token_count  INT          NOT NULL,
    masked       BOOLEAN      NOT NULL,
    created_at   BIGINT       NOT NULL,
    metadata     LONGTEXT,
    PRIMARY KEY (message_id),
    INDEX idx_med_message_15_session (session_id),
    INDEX idx_med_message_15_session_created (session_id, created_at)
);
