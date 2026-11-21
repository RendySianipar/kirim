# ADR 0007: Daily reconciliation

**Status:** accepted

## Context
Idempotency, row locks and the outbox make each transfer correct. They cannot catch damage done *around* the code: a bad manual SQL fix, a bug in a future release, a migration that touches balances, an event stuck in the outbox. Banks therefore check the books every day and want a stored, reviewable answer to "does the money add up?".

## Decision
A reconciliation run checks, for one business day (Asia/Jakarta):

| Check | Scope | Mismatch |
| --- | --- | --- |
| Stored balance = sum of the account's ledger entries | all accounts, all time | `BALANCE_MISMATCH` |
| Per currency, all balances = all opening deposits | all time | `MONEY_NOT_CONSERVED` |
| Each COMPLETED transfer has exactly 1 DEBIT on the sender and 1 CREDIT on the receiver, both for its amount | transfers of the day | `TRANSFER_LEGS_MISMATCH` |
| Each REJECTED transfer has no ledger entries | transfers of the day | `REJECTED_WITH_LEDGER_ENTRIES` |
| Each transfer has exactly one outbox event, PUBLISHED (or PENDING for less than 5 minutes) | transfers of the day | `EVENT_MISSING`, `EVENT_DUPLICATED`, `EVENT_NOT_PUBLISHED` |

- **Set-based SQL.** Each check is one aggregate query that returns only the problems; the database does the work, and each query can be reviewed on its own.
- **One consistent snapshot.** All checks run in one `REPEATABLE READ`, read-only transaction, so transfers committing during the run can never show up half-done (a debit without its credit).
- **Stored results.** Every run and every mismatch (up to 1000 per run; the count is always exact) is saved in `reconciliation_runs` / `reconciliation_mismatches` and exposed at `/api/v1/reconciliations`, plus metrics `kirim_reconciliation_runs_total{status}` and `kirim_reconciliation_mismatches_total{kind}` to alert on.
- **Nightly, once.** A scheduler runs at 01:30 WIB for the previous day. Every API replica has the scheduler; a partial unique index (one `SCHEDULED` run per business date) lets the first replica claim the day and the others skip, the same idea as idempotency keys. Manual runs are always allowed.
- **Report, never repair.** The job only detects. Fixing money is a human decision with an audit trail, not something a batch job should do on its own.

## Consequences
- The balance checks scan all accounts and ledger entries every night. Fine at this size; at scale they would use per-day ledger totals carried forward (opening balance of the day + movements of the day) instead of all history.
- This is internal reconciliation (our ledger against itself). Reconciling against an external party (a bank statement or a payment partner's settlement file) would add a step that loads their file and matches it line by line against our transfers; the run/mismatch tables are built to hold that too.
- A failed scheduled run is marked `FAILED` and keeps the day's slot; Operations reruns it manually.
