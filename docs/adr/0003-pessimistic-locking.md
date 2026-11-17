# ADR 0003: Pessimistic row locks in a fixed order

**Status:** accepted

## Context
Two transfers touching the same account at the same time must not both read the old balance (a lost update). Transfers A→B and B→A at the same time can deadlock if each locks its "from" account first.

## Decision
- Lock both accounts with a single `SELECT ... WHERE id IN (:a, :b) ORDER BY id FOR UPDATE`. Every transaction takes its locks in the same global order, so lock waits can't form a cycle.
- Store money as `BIGINT` minor units, with `CHECK (balance >= 0)` as a last line of defence below the application rule.
- `Math.addExact` on credits, so overflow throws instead of silently wrapping.

## Consequences
- Very hot accounts (e.g. a merchant receiving thousands of payments per second) serialise on one row. At that scale you'd shard the balance or credit asynchronously; that's out of scope here.
- `ConcurrentTransferIntegrationTest` is the executable proof: 200 concurrent random transfers across 3 accounts, checking total conservation, no negative balances, ledger = balance, and no deadlocks.
- Alternative considered: optimistic locking (`version` column + retry). It's simpler with ORMs, but under contention most attempts fail and retry, and the retry policy leaks into the API.
