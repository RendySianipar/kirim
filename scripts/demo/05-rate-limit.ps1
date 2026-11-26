# Kirim demo, feature 5: per-account rate limit in Redis (10 new transfers per minute). Run 01-setup.ps1 first.
# Takes about 1-2 minutes because it waits for the next rate-limit window.

Wait-FreshMinute

Step "Eleven transfers from Carol within one minute"
$results = 1..11 | ForEach-Object { Send-Transfer $carol.id $bob.id 10000 }
$results | Format-Table Http, Status, Code, RetryAfter -AutoSize
Expect "ten 201 COMPLETED, then 429 RATE_LIMITED with RetryAfter = seconds to the next minute"
"Carol's balance: $(Get-Balance $carol)"
Expect "900000: the 11th request moved no money"

Step "The counter in Redis"
$window = [math]::Floor([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() / 60)
$rlKey = "kirim:rl:transfers:$($carol.id):$window"
"key: $rlKey"
"count: $(dc exec -T redis redis-cli GET $rlKey)   ttl: $(dc exec -T redis redis-cli TTL $rlKey) s"
Expect "count 11, ttl <= 60 (Redis cleans up by itself)"

Step "A limited key is not burned, and replays use no quota"
$k = "demo-rl-$run"
$limited = Send-Transfer $carol.id $bob.id 10000 $k
$limited | Format-Table Http, Code, RetryAfter -AutoSize
Expect "429 RATE_LIMITED (still the same minute)"
$wait = 61
if ($limited.RetryAfter) { $wait = [int]$limited.RetryAfter + 1 }
Write-Host "   waiting $wait s (Retry-After)..." -ForegroundColor DarkGray
Start-Sleep $wait
Send-Transfer $carol.id $bob.id 10000 $k | Format-Table Http, Replayed, Status -AutoSize
Send-Transfer $carol.id $bob.id 10000 $k | Format-Table Http, Replayed, Status -AutoSize
Expect "201 COMPLETED with the same key, then 200 Replayed=true"

Step "Bonus: the 20th transfer today raises HIGH_DAILY_COUNT"
1..9 | ForEach-Object { Send-Transfer $carol.id $bob.id 10000 } | Format-Table Http, Status -AutoSize
Start-Sleep 3
Invoke-Kirim "$risk/alerts?accountId=$($carol.id)" -As ops | Format-Table rule, details -AutoSize
Expect "one HIGH_DAILY_COUNT alert: '20 transfers today (threshold 20)'"
