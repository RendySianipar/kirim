# ADR 0001: Idempotency keys for transfers

**Status:** accepted

## Context
Mobile networks drop responses. A user taps "Send", the request succeeds on the server, but the response never arrives; the app retries, or the user taps again. Without protection, that's a double debit.

## Decision
- Clients send an `Idempotency-Key` header, generated **once per user intent** and reused on every retry.
- The server stores `(key, sha256(request body), transfer_id)` in `idempotency_keys`, **in the same transaction** as the transfer.
- The key is reserved with `INSERT ... ON CONFLICT DO NOTHING` as the first step. PostgreSQL makes a concurrent insert of the same key **wait** for the first transaction to finish, so a double-tap can't run two transfers. The second caller then replays the committed result.
- Same key + same body → `200` with the original transfer and `Idempotent-Replayed: true`.
- Same key + different body → `422 IDEMPOTENCY_KEY_REUSED`.
- Validation errors (unknown account, etc.) roll back the reservation, so a corrected request can reuse the key.
- Business rejections (insufficient funds) **are** stored, so retries get the same answer.

## Consequences
- The key table grows forever until a TTL job is added (roadmap).
- Holding the key row's lock for the whole transfer serialises retries of one key. That's intended, and transfers are short.
- Alternative considered: Redis `SETNX`. Rejected because it can't be atomic with the PostgreSQL write, which brings back the dual-write problem.
