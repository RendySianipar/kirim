# Kirim demo, feature 4: Java risk service, AML-style alerts. Run 01-setup.ps1 first.
# Rules: LARGE_TRANSFER >= Rp 25 juta; DAILY_TOTAL_EXCEEDED once when today's total reaches Rp 100 juta;
# HIGH_DAILY_COUNT once on the 20th transfer of the day (see 05-rate-limit.ps1). Days are Jakarta days.
# Alerts are for the OPS role only, so they are read as the "ops" user.

Step "Three large transfers from Alice"
$big = @(
  Send-Transfer $alice.id $bob.id 30000000   # 30 juta
  Send-Transfer $alice.id $bob.id 30000000   # ~60 juta today
  Send-Transfer $alice.id $bob.id 45000000   # ~105 juta: crosses 100 juta
)
$big | Format-Table Http, Status, Amount -AutoSize
Expect "three 201 COMPLETED"
Start-Sleep 3

Step "Alerts for Alice"
Invoke-Kirim "$risk/alerts?accountId=$($alice.id)" -As ops | Format-Table rule, amount, details -AutoSize
Expect "3x LARGE_TRANSFER, 1x DAILY_TOTAL_EXCEEDED (on the 45 juta transfer)"

Step "Alice's activity today (Jakarta time)"
Invoke-Kirim "$risk/accounts/$($alice.id)/daily-activity" -As ops | Format-List
Expect "totalAmount ~105 juta, transferCount = Alice's completed transfers today"

Step "One more large transfer: no second daily-total alert"
Send-Transfer $alice.id $bob.id 26000000 | Format-Table Http, Status, Amount -AutoSize
Start-Sleep 3
$alerts = Invoke-Kirim "$risk/alerts?accountId=$($alice.id)" -As ops
$alerts | Group-Object rule | Format-Table Name, Count -AutoSize
Expect "LARGE_TRANSFER 4, DAILY_TOTAL_EXCEEDED still 1"
