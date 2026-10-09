-- Flyway migration: cold-tier archive of archived consultation transcripts (D48).
--
-- D47 flips med_session.status to ARCHIVED (2) and evicts the Redis window, but the transcript
-- itself never moves: it stays in the 16 sharded med_message_* tables. So "this consultation is
-- archived" had no verifiable counterpart in storage - no cold copy, and no digest that could prove
-- a cold copy complete. These two tables are that counterpart.
--
-- Why the archive is NOT sharded (unlike med_message):
--   * it is read one session at a time, and the composite primary key (session_id, message_id) makes
--     a session's transcript one contiguous range read instead of a scatter-gather across shards;
--   * the sharding rule exists to keep the *write* path of live consultations cheap. Cold data has no
--     write path: a session is exported once and never touched again.
-- ShardingSphere-JDBC serves both tables through its SINGLE rule (sharding/med-sharding.yaml).
--
-- Why the payload is the Protobuf binary rather than decoded columns:
--   * ROADMAP section 4 fixes Protobuf as the storage encoding, and storing the frozen bytes is what
--     makes the cold copy readable by the heterogeneous Python middleware from med_session.proto
--     alone - the first executable landing point of the "two systems can read each other's data"
--     promise;
--   * the identity columns below are a denormalized copy for operator queries (counting rows per
--     session/tenant without decoding anything). They are written from the same entity as the payload
--     in the same statement and there is no update path, so they cannot drift from it. The payload
--     stays the authority.
--
-- payload_checksum is the hex SHA-256 of the canonical transcript form: for every message in
-- (created_at, message_id) order, the line "<message_id>\t<created_at>\t<sha256hex(payload)>",
-- joined by '\n' and hashed once more. Empty transcripts hash to sha256(""), which is what lets
-- "exported, zero messages" be distinguished from "never exported" - the manifest row is the only
-- thing that marks a session as exported, and it is written only after the cold copy has been read
-- back and compared.
--
-- The DDL is intentionally backend-agnostic: it runs unchanged against MySQL (production) and
-- against an in-memory H2 schema in MySQL compatibility mode (tests). Index names carry the table
-- name because H2 requires schema-wide unique index names.

CREATE TABLE IF NOT EXISTS med_message_archive (
    session_id   VARCHAR(64)  NOT NULL,
    message_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    dept_id      VARCHAR(64)  NOT NULL,
    patient_id   VARCHAR(64)  NOT NULL,
    created_at   BIGINT       NOT NULL,
    archived_at  BIGINT       NOT NULL,
    payload      LONGBLOB     NOT NULL,
    PRIMARY KEY (session_id, message_id),
    INDEX idx_med_message_archive_session (session_id, created_at, message_id)
);

-- One row per exported session: the export manifest. Its primary key is what makes "already
-- exported" a fact rather than an assumption, and its checksum is what a verification re-computes.
-- tenant_id / dept_id / patient_id are carried over so a future scoped purge (deliberately NOT part
-- of D48 - deleting hot rows is irreversible and needs its own guard) can be expressed as a scoped
-- statement without decoding a single payload.
CREATE TABLE IF NOT EXISTS med_session_archive (
    session_id       VARCHAR(64)  NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL,
    dept_id          VARCHAR(64)  NOT NULL,
    patient_id       VARCHAR(64)  NOT NULL,
    message_count    INT          NOT NULL,
    payload_checksum CHAR(64)     NOT NULL,
    exported_at      BIGINT       NOT NULL,
    PRIMARY KEY (session_id),
    INDEX idx_med_session_archive_exported (exported_at)
);
