# Kirim demo, feature 8: business rejections vs. validation errors. Run 01-setup.ps1 first.
# A rejection (no money, over the limit) is a recorded transfer with status REJECTED (201),
# so a retry gets the same answer. A validation error records nothing and is a Problem Details error.

$dave = Open-Account "Dave $run" 0 -As dave

Step "Business rejections"
$k = "demo-reject-$run"
Send-Transfer $dave.id  $bob.id 1000     $k | Format-Table Http, Replayed, Status, Reason -AutoSize
Send-Transfer $dave.id  $bob.id 1000     $k | Format-Table Http, Replayed, Status, Reason -AutoSize
Send-Transfer $alice.id $bob.id 60000000    | Format-Table Http, Status, Reason -AutoSize
Expect "201 REJECTED INSUFFICIENT_FUNDS | 200 Replayed=true, same rejection | 201 REJECTED LIMIT_EXCEEDED (max Rp 50 juta)"

Step "Validation errors"
Send-Transfer $alice.id $alice.id 1000                   | Format-Table Http, Code, Detail -AutoSize
Send-Transfer $alice.id ([guid]::NewGuid().ToString()) 1000 | Format-Table Http, Code, Detail -AutoSize
Send-Transfer $alice.id $bob.id 0                        | Format-Table Http, Code, Detail -AutoSize
Expect "400 INVALID_REQUEST (same account) | 404 ACCOUNT_NOT_FOUND | 400 (amount must be positive)"

Start-Sleep 2
Step "Dave's notification (via Kafka)"
Invoke-Kirim "$api/accounts/$($dave.id)/notifications" -As dave | Format-Table message -AutoSize
Expect "'Your transfer of IDR 1,000 was rejected (INSUFFICIENT_FUNDS)'"
