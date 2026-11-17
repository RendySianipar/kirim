# ADR 0002: Transactional outbox for transfer events

**Status:** accepted

## Context
Other services (notifications, fraud, analytics) need to know when a transfer happens. Publishing to Kafka inside the request handler is a **dual write**: the DB can commit while the publish fails (event lost), or the publish succeeds while the DB rolls back (event about a transfer that never happened).

## Decision
- The transfer transaction also inserts a row into `outbox_events`. The event exists **if and only if** the transfer committed.
- A relay polls due rows with `FOR UPDATE SKIP LOCKED` (safe with many app instances), publishes with `acks=all` and an idempotent producer, then marks them `PUBLISHED`.
- On failure: `attempts += 1` and `next_attempt_at = now + backoff(attempts)`, with exponential backoff capped at 5 minutes. After `max-attempts` the row becomes `FAILED`, an alertable state rather than an infinite loop.
- Delivery is **at-least-once**. Consumers must be idempotent: the notification consumer records `event_id` in `processed_events` in the same transaction as its own write.
- Consumer failures are retried (0.5s → 4s), then routed to `<topic>-dlt`. Deserialization errors skip the retries.

## Consequences
- Events show up a little after the commit (poll interval 500ms). The UI polls to show this eventual consistency.
- Ordering is per Kafka partition (keyed by transfer ID). Nothing currently relies on ordering across transfers.
- Alternative considered: CDC with Debezium reading the WAL. That's lower latency and needs no polling, but it's more infrastructure than this project needs. It would be the next step at scale.
