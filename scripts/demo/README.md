# Demo scripts: try every feature from PowerShell

Each script demonstrates one feature against the running Docker Compose stack and prints what it got next to what to expect (grey `expected:` lines). They work in Windows PowerShell 5.1 and PowerShell 7.

## Run

```powershell
# 1. Start the stack (from the repo root)
docker compose up -d            # first time, or after changing the code: docker compose up --build -d

# 2. In the same PowerShell window: helpers + fresh demo accounts
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass   # only if scripts are blocked; this window only
. .\scripts\demo\01-setup.ps1

# 3. Any feature, in any order (02 before 03 gives 03 something to show)
. .\scripts\demo\02-idempotency.ps1
```

Dot-source every script (the leading `. `), so `$alice`, `$bob`, `Send-Transfer` and the rest stay defined for the next script. A new window needs `01-setup.ps1` again; each run opens new accounts and uses new idempotency keys, so scripts can be re-run at any time.

## Scripts

| Script | Feature | What it shows |
| --- | --- | --- |
| `01-setup.ps1` | Accounts | Waits for both services to be ready, defines the helpers, opens Alice (Rp 200 juta), Bob and Carol (Rp 1 juta) |
| `02-idempotency.ps1` | Idempotent transfers | Same key: `201`, then `200` replayed, then `422` for a changed body; money moves once; 5 simultaneous taps (PowerShell 7) |
| `03-outbox-kafka.ps1` | Ledger, outbox, Kafka | Ledger entries, outbox rows `PUBLISHED`, events on `transfer-events`, two consumer groups, notifications |
| `03b-kafka-down.ps1` | Outbox resilience | **Stops Kafka**: the transfer still succeeds, the event waits as `PENDING` and is sent after Kafka is back |
| `04-risk-alerts.ps1` | Risk service (Java) | `LARGE_TRANSFER` and a single `DAILY_TOTAL_EXCEEDED` alert, daily activity per Jakarta day |
| `05-rate-limit.ps1` | Rate limit (Redis) | 11th transfer in a minute is `429` + `Retry-After`; the counter in Redis; a limited key is not burned; `HIGH_DAILY_COUNT` on the 20th transfer. Takes 1-2 minutes |
| `06-cache-metrics.ps1` | Cache (Redis), metrics | Cache miss then hit for `GET /transfers/{id}`, the entry and its TTL in Redis, Prometheus counters |
| `07-redis-down.ps1` | Fail open | **Stops Redis**: transfers still work, readiness stays `UP`, overall health `DOWN` |
| `08-rejections.ps1` | Errors | `REJECTED` transfers (insufficient funds, over Rp 50 juta) vs. `400`/`404` validation errors |
| `09-reconciliation.ps1` | Daily reconciliation | Books `MATCHED`; a balance changed by hand is caught as `BALANCE_MISMATCH` + `MONEY_NOT_CONSERVED`; repaired → `MATCHED`; run history and metrics |

`09` changes one balance on purpose and puts it back. `03b` and `07` stop a container and start it again in a `finally` block, so it comes back even if the script fails halfway.

## Ports

The scripts read `API_PORT` from the repo's `.env` (the same file `docker compose` reads), default `8080`. The risk service is always on `8081`. If a port is taken on your machine:

```powershell
Set-Content -Path .env -Value "API_PORT=8090","REDIS_PORT=6380" -Encoding ascii
docker compose up -d --force-recreate
```

## Start from an empty database

```powershell
docker compose down -v      # deletes accounts, transfers, alerts and events
docker compose up -d
. .\scripts\demo\01-setup.ps1
```

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `Not ready after 90 s` from `01-setup.ps1` | `docker compose ps`; `docker compose logs api --tail 50` |
| `The term 'Send-Transfer' is not recognized` | New window, or the script was run without the leading `. ` → dot-source `01-setup.ps1` again |
| The 11th transfer in `05` is not `429` | The burst crossed a minute boundary; run `05` again |
| Alerts or notifications missing | Events are asynchronous (outbox → Kafka → consumer): wait a few seconds and query again |
