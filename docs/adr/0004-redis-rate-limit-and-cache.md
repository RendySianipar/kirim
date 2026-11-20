# ADR 0004: Redis for rate limiting and caching, not for correctness

**Status:** accepted

## Context
Two needs came up once the API could run as several replicas:
1. **Abuse protection.** A compromised session or a buggy client can fire hundreds of transfers a minute from one account. Balance and per-transfer limits stop money from going negative, but not the flood itself.
2. **Cheap reads.** `GET /transfers/{id}` is called on every retry and status poll, and each call hits PostgreSQL.

An in-memory limiter or cache would be per-pod: with 2 replicas, a client gets 2x the limit and each pod has its own cache. The state has to be shared, so it lives in Redis.

## Decision
- **Rate limit:** fixed window per source account (`kirim:rl:transfers:{account}:{window}`), default 10 new transfers per minute. `INCR` + `EXPIRE` run in one Lua script, so a counter can never be left without a TTL. Over the limit: `429` with `Retry-After`.
- The check runs **after** the idempotency key is reserved, so replays of an earlier request use no quota, and a `429` rolls back the reservation so the client can retry later with the same key.
- **Cache:** cache-aside for transfers only, TTL 10 minutes. Transfers are immutable once committed, so there is nothing to invalidate. Balances are **not** cached: they change on every transfer and a stale balance in a bank is a bug.
- Entries are written on the read path only, never inside the write transaction (which could still roll back).
- **Redis is never the source of truth.** Idempotency stays in PostgreSQL (see ADR 0001: Redis can't be atomic with the PostgreSQL write).

## Failure behaviour
- Redis down → the limiter **fails open** by default (`kirim.rate-limit.fail-open`), cache reads fall through to PostgreSQL. Transfers keep working; money rules are still enforced by PostgreSQL. Set `fail-open: false` to reject with `503` instead, if abuse protection matters more than availability.
- Redis timeouts are 500 ms, because the limiter call happens while the idempotency row lock is held.
- Redis is excluded from the Kubernetes readiness probe, so a Redis outage doesn't take every API pod out of the Service.

## Consequences
- Fixed windows allow up to 2x the limit across a window boundary. Acceptable for a guard rail; a sliding window or token bucket is the upgrade path.
- Redis runs without persistence: a restart resets counters and empties the cache, which is harmless.
