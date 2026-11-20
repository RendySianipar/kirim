-- The risk service's own database. It is built only from Kafka events.

-- Consumer-side dedupe: at-least-once delivery becomes effectively-once.
CREATE TABLE processed_events (
    event_id      UUID PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL
);

-- Running totals per sending account per business day. Updated with an atomic
-- upsert, so two consumers can never lose an increment.
CREATE TABLE daily_outgoing (
    account_id      UUID    NOT NULL,
    day             DATE    NOT NULL,
    total_amount    BIGINT  NOT NULL CHECK (total_amount >= 0),
    transfer_count  INT     NOT NULL CHECK (transfer_count >= 0),
    PRIMARY KEY (account_id, day)
);

CREATE TABLE alerts (
    id           UUID PRIMARY KEY,
    event_id     UUID         NOT NULL REFERENCES processed_events (event_id),
    transfer_id  UUID         NOT NULL,
    account_id   UUID         NOT NULL,
    rule         VARCHAR(40)  NOT NULL,
    amount       BIGINT       NOT NULL,
    details      VARCHAR(200) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    UNIQUE (transfer_id, rule)
);
CREATE INDEX idx_alerts_account ON alerts (account_id, created_at DESC);
CREATE INDEX idx_alerts_created ON alerts (created_at DESC);
