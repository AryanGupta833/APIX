-- One-time corrective migration only for schemas where an earlier Hibernate update
-- created the @Version column as nullable before its mapping was marked NOT NULL.
UPDATE api_suite_runs SET version = 0 WHERE version IS NULL;

ALTER TABLE api_suite_runs
    MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0;
