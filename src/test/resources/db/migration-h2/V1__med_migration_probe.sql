-- Test-only migration used by MedFlywayConfigTest.
--
-- The offline suite exercises the migration wiring against an in-memory H2 database, because the
-- production migrations in db/migration are MySQL DDL (ENGINE=InnoDB, 16 physical shard tables) that
-- H2 cannot parse. This single statement is all the test needs: if the Flyway bean really ran during
-- context refresh, the table exists and flyway_schema_history has one applied migration.
--
-- It is never shipped: it lives under src/test/resources, so it cannot be picked up by a deployment
-- whose locations point at classpath:db/migration.

CREATE TABLE med_migration_probe (
    id INT PRIMARY KEY,
    note VARCHAR(64) NOT NULL
);
