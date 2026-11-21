-- Daily reconciliation: does the money add up? Each run checks the books and
-- stores what it found, so Operations can see every run and every mismatch.

CREATE TABLE reconciliation_runs (
    id                 UUID PRIMARY KEY,
    business_date      DATE        NOT NULL,             -- the day whose transfers were checked (Asia/Jakarta)
    run_type           VARCHAR(10) NOT NULL,             -- SCHEDULED | MANUAL
    status             VARCHAR(10) NOT NULL,             -- RUNNING | MATCHED | MISMATCH | FAILED
    accounts_checked   INT,
    transfers_checked  INT,
    mismatch_count     INT,
    error              TEXT,
    started_at         TIMESTAMPTZ NOT NULL,
    finished_at        TIMESTAMPTZ
);
-- At most one scheduled run per day, even with several API replicas: the
-- replica whose INSERT wins runs it, the others skip (same idea as idempotency keys).
CREATE UNIQUE INDEX uq_reconciliation_scheduled_day
    ON reconciliation_runs (business_date) WHERE run_type = 'SCHEDULED';
CREATE INDEX idx_reconciliation_runs_started ON reconciliation_runs (started_at DESC);

CREATE TABLE reconciliation_mismatches (
    id           UUID PRIMARY KEY,
    run_id       UUID         NOT NULL REFERENCES reconciliation_runs (id),
    kind         VARCHAR(40)  NOT NULL,
    account_id   UUID,
    transfer_id  UUID,
    currency     CHAR(3),
    expected     BIGINT,
    actual       BIGINT,
    details      VARCHAR(300) NOT NULL
);
CREATE INDEX idx_reconciliation_mismatches_run ON reconciliation_mismatches (run_id);
