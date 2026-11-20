# Kirim demo, feature 3b: transfers keep working while Kafka is down. DISRUPTIVE: stops Kafka.
# Run 01-setup.ps1 first. Kafka is started again at the end, even if something fails.

try {
  Step "Stopping Kafka"
  dc stop kafka

  Step "A transfer while Kafka is down"
  Send-Transfer $alice.id $bob.id 20000 | Format-Table Http, Status, Amount -AutoSize
  Expect "201 COMPLETED: the transfer does not need Kafka"

  Start-Sleep 5
  Step "Its event waits in the outbox"
  dc exec -T postgres psql -U kirim -d kirim -c "SELECT status, attempts, left(last_error, 60) AS last_error FROM outbox_events WHERE status <> 'PUBLISHED';"
  Expect "a PENDING row; attempts goes up with backoff"
} finally {
  Step "Starting Kafka again"
  dc start kafka
}

Write-Host "   waiting 30 s for Kafka and the relay's next attempt..." -ForegroundColor DarkGray
Start-Sleep 30
dc exec -T postgres psql -U kirim -d kirim -c "SELECT count(*) AS not_published FROM outbox_events WHERE status <> 'PUBLISHED';"
Expect "not_published = 0 (if not, wait a little and run that query again: the backoff can be up to a minute)"
