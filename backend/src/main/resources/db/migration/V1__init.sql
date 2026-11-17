-- Money is stored as BIGINT in minor units (whole Rupiah for IDR). Never floats.

CREATE TABLE accounts (
    id          UUID PRIMARY KEY,
    owner_name  VARCHAR(100) NOT NULL,
    currency    CHAR(3)      NOT NULL,
    balance     BIGINT       NOT NULL CHECK (balance >= 0),
    created_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE transfers (
    id               UUID PRIMARY KEY,
    from_account_id  UUID        NOT NULL REFERENCES accounts (id),
    to_account_id    UUID        NOT NULL REFERENCES accounts (id),
    amount           BIGINT      NOT NULL CHECK (amount > 0),
    currency         CHAR(3)     NOT NULL,
    description      VARCHAR(140),
    status           VARCHAR(20) NOT NULL,
    failure_reason   VARCHAR(50),
    created_at       TIMESTAMPTZ NOT NULL
);

-- Double-entry style ledger: every balance change has a matching row.
CREATE TABLE ledger_entries (
    id             UUID PRIMARY KEY,
    account_id     UUID        NOT NULL REFERENCES accounts (id),
    transfer_id    UUID REFERENCES transfers (id), -- NULL for the opening deposit
    entry_type     VARCHAR(10) NOT NULL,           -- DEBIT | CREDIT
    amount         BIGINT      NOT NULL CHECK (amount > 0),
    balance_after  BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id, created_at DESC);

-- One row per client-supplied Idempotency-Key. The PRIMARY KEY is what makes
-- concurrent retries safe: a second INSERT waits for the first transaction.
CREATE TABLE idempotency_keys (
    idempotency_key  VARCHAR(100) PRIMARY KEY,
    request_hash     CHAR(64)     NOT NULL,
    transfer_id      UUID REFERENCES transfers (id),
    created_at       TIMESTAMPTZ  NOT NULL
);

-- Transactional outbox: events are written in the same DB transaction as the
-- transfer, then relayed to Kafka with retries.
CREATE TABLE outbox_events (
    id               UUID PRIMARY KEY,
    aggregate_id     UUID        NOT NULL,
    event_type       VARCHAR(50) NOT NULL,
    payload          TEXT        NOT NULL,
    status           VARCHAR(20) NOT NULL,         -- PENDING | PUBLISHED | FAILED
    attempts         INT         NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL,
    last_error       TEXT,
    created_at       TIMESTAMPTZ NOT NULL,
    published_at     TIMESTAMPTZ
);
CREATE INDEX idx_outbox_events_pending ON outbox_events (next_attempt_at) WHERE status = 'PENDING';

-- Consumer side: dedupe table so at-least-once delivery becomes effectively-once.
CREATE TABLE processed_events (
    event_id      UUID PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE notifications (
    id          UUID PRIMARY KEY,
    event_id    UUID        NOT NULL REFERENCES processed_events (event_id),
    account_id  UUID        NOT NULL,
    message     TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_notifications_account ON notifications (account_id, created_at DESC);
