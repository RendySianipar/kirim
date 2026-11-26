# Kirim demo, feature 2: idempotent transfers. Run 01-setup.ps1 first (dot-sourced).
# Each transfer carries an Idempotency-Key; however often the same request is sent, money moves once.

Step "Same request three ways with one key"
$key = "demo-idem-$run"
$first  = Send-Transfer $alice.id $bob.id 150000 $key
$retry  = Send-Transfer $alice.id $bob.id 150000 $key
$reused = Send-Transfer $alice.id $bob.id 999    $key
$first, $retry, $reused | Format-Table Http, Replayed, Status, Code, Amount, Id -AutoSize
Expect "201 COMPLETED | 200 Replayed=true, same Id | 422 IDEMPOTENCY_KEY_REUSED"

Step "Balances: the money moved exactly once"
"Alice: $(Get-Balance $alice)   Bob: $(Get-Balance $bob)"
Expect "Alice 199850000, Bob 150000"

if ($PSVersionTable.PSVersion.Major -ge 7) {
  Step "Five simultaneous taps with the same key (PowerShell 7)"
  $k = "demo-tap-$run"
  $body = @{ fromAccountId = $alice.id; toAccountId = $bob.id; amount = 1000 } | ConvertTo-Json
  $url = "$api/transfers"
  $token = Get-KirimToken alice
  1..5 | ForEach-Object -Parallel {
    $r = Invoke-WebRequest -Method Post $using:url -ContentType "application/json" `
           -Headers @{ "Idempotency-Key" = $using:k; Authorization = "Bearer $($using:token)" } -Body $using:body -SkipHttpErrorCheck
    "$([int]$r.StatusCode) replayed=$($r.Headers['Idempotent-Replayed'])"
  } | Sort-Object
  Expect "one 201, four 200 replayed=true; Alice is charged 1000 once"
} else {
  Write-Host "   (simultaneous-taps demo needs PowerShell 7: skipped)" -ForegroundColor DarkGray
}
