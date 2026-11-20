# ADR 0005: A separate risk service, in Java, fed only by Kafka

**Status:** accepted

## Context
Banks must watch for suspicious activity (AML): unusually large transfers, accounts sending a lot in one day, sudden bursts of transfers. Putting these checks inside the transfer transaction would slow every transfer and couple two very different concerns: moving money correctly vs. analysing behaviour.

## Decision
- A new service, `risk-service`, written in **Java 21 / Spring Boot**, consumes `transfer-events` in its **own consumer group** (`kirim-risk`). It receives every event independently of the notification consumer.
- **Database per service:** it has its own database (`kirim_risk`) and never reads the transfer service's tables. Its only input is the event contract.
- Rules are a pure function (`RiskRules`) with unit tests: `LARGE_TRANSFER`, `DAILY_TOTAL_EXCEEDED`, `HIGH_DAILY_COUNT`. Daily rules fire **once**, on the transfer that crosses the threshold, so one busy account doesn't flood the alert queue.
- Days are bucketed in `Asia/Jakarta`, not UTC: a transfer at 01:00 WIB belongs to that WIB day.
- Daily totals are updated with one atomic upsert (`INSERT ... ON CONFLICT DO UPDATE ... RETURNING`), so concurrent consumers can't lose an increment.
- Same reliability pattern as the notification consumer: dedupe on `eventId` in the same transaction, retries with backoff, then its **own** dead-letter topic `transfer-events-risk-dlt`.
- The event DTO uses strings for `type`/`status`, and unknown fields are ignored, so the producer can evolve without breaking this consumer.

## Consequences
- Alerts are **after the fact** (milliseconds to seconds later); this service detects, it does not block. Blocking a transfer in real time would need a synchronous check in the transfer path, with its own latency and availability trade-offs.
- Two languages in one repo. That is deliberate here (Kotlin and Java both run on the JVM and use the same Spring stack) and mirrors teams where services are owned by different groups.
