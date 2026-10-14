-- Manual MySQL migration. Apply once before deploying the async suite-run code.
-- New progress fields default safely; known totals are backfilled below and
-- the optimistic-lock version starts at zero for existing rows.
ALTER TABLE api_suite_runs
    ADD COLUMN total_cases INT NOT NULL DEFAULT 0,
    ADD COLUMN completed_cases INT NOT NULL DEFAULT 0,
    ADD COLUMN current_case_id VARCHAR(100) NULL,
    ADD COLUMN current_case_name VARCHAR(200) NULL,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Older synchronous run rows already have terminal case counters. Use those
-- as the known total; in-flight legacy records will be marked interrupted at startup.
UPDATE api_suite_runs
SET completed_cases = passed + failed + skipped + errored,
    total_cases = passed + failed + skipped + errored;
