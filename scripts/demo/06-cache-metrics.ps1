# Kirim demo, feature 6: cache-aside for transfers in Redis, and metrics. Run 01-setup.ps1 first.
# Transfers never change after commit, so a cached copy can never go stale. Balances are not cached.

Step "A new transfer is not cached (it was written inside a transaction)"
$t = Send-Transfer $bob.id $alice.id 5000
"cached: $(dc exec -T redis redis-cli EXISTS "kirim:transfer:$($t.Id)")"
Expect "0"

Step "Read it twice: miss, then hit"
$before = Get-Metric 'kirim_cache_total'
Invoke-Kirim "$api/transfers/$($t.Id)" -As bob | Format-List id, amount, status
Invoke-Kirim "$api/transfers/$($t.Id)" -As bob | Format-List id, amount, status
$after = Get-Metric 'kirim_cache_total'
"before:"; $before
"after:";  $after
Expect "result=miss and result=hit each +1"

Step "The cached entry in Redis"
"exists: $(dc exec -T redis redis-cli EXISTS "kirim:transfer:$($t.Id)")   ttl: $(dc exec -T redis redis-cli TTL "kirim:transfer:$($t.Id)") s"
Expect "exists 1, ttl <= 600"

Step "Other metrics"
Get-Metric 'kirim_ratelimit_total'
Get-Metric 'kirim_transfers_total'
Get-Metric 'kirim_outbox'
Get-Metric 'kirim_risk' $RiskPort
