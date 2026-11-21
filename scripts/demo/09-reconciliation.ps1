# Kirim demo, feature 9: daily reconciliation. Run 01-setup.ps1 first (and some other demos, so there is data).
# Checks that the books add up: balances = ledger, no money created or lost,
# every transfer of the day has its ledger legs and one delivered Kafka event.
# Runs every night at 01:30 WIB for the previous day; here we run it by hand.
# DISRUPTIVE to the data: it breaks one balance on purpose, then repairs it.

Step "Reconcile today: the books should match"
$r = Invoke-RestMethod -Method Post "$api/reconciliations"   # no date = today (WIB)
$today = $r.run.businessDate
$r.run | Format-List businessDate, runType, status, accountsChecked, transfersChecked, mismatchCount
Expect "status MATCHED, mismatchCount 0 (EVENT_NOT_PUBLISHED here means Kafka was down: rerun after a minute)"

Step "Break the books: change Bob's balance without a transfer (a bad manual fix)"
dc exec -T postgres psql -U kirim -d kirim -c "UPDATE accounts SET balance = balance + 5000 WHERE id = '$($bob.id)';"

Step "Reconcile again"
$r = Invoke-RestMethod -Method Post "$api/reconciliations?date=$today"
$r.run | Format-List status, mismatchCount
$r.mismatches | Format-Table kind, accountId, currency, expected, actual, details -AutoSize
Expect "MISMATCH: BALANCE_MISMATCH for Bob (actual = expected + 5000) and MONEY_NOT_CONSERVED for IDR (+5000)"

Step "Repair the balance and reconcile once more"
dc exec -T postgres psql -U kirim -d kirim -c "UPDATE accounts SET balance = balance - 5000 WHERE id = '$($bob.id)';"
(Invoke-RestMethod -Method Post "$api/reconciliations?date=$today").run | Format-List status, mismatchCount
Expect "MATCHED again"

Step "Run history (newest first)"
Invoke-RestMethod "$api/reconciliations" | Select-Object -First 5 | Format-Table businessDate, runType, status, transfersChecked, mismatchCount, startedAt -AutoSize
Get-Metric 'kirim_reconciliation'
Expect "every run is stored; metrics count runs per status and mismatches per kind"
