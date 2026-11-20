# Kirim demo, feature 3: ledger, transactional outbox, Kafka, notifications. Read-only.
# Run 01-setup.ps1 and 02-idempotency.ps1 first, so there are transfers to look at.

Start-Sleep 2   # outbox relay polls every 500 ms; give the consumers a moment

Step "Ledger entries (newest first)"
Invoke-RestMethod "$api/accounts/$($alice.id)/entries?limit=5" | Format-Table type, amount, balanceAfter, createdAt -AutoSize
Invoke-RestMethod "$api/accounts/$($bob.id)/entries?limit=5"   | Format-Table type, amount, balanceAfter, createdAt -AutoSize
Expect "Alice has DEBIT 150000; Bob has CREDIT 150000"

Step "Notifications written by the Kafka consumer"
Invoke-RestMethod "$api/accounts/$($alice.id)/notifications" | Format-Table message, createdAt -AutoSize
Invoke-RestMethod "$api/accounts/$($bob.id)/notifications"   | Format-Table message, createdAt -AutoSize
Expect "'You sent IDR 150,000' for Alice, 'You received IDR 150,000' for Bob"

Step "Outbox table (written in the same transaction as the transfer)"
dc exec -T postgres psql -U kirim -d kirim -c "SELECT event_type, status, attempts, published_at FROM outbox_events ORDER BY created_at DESC LIMIT 5;"
Expect "every row PUBLISHED"

Step "Events on the transfer-events topic"
dc exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:19092 --topic transfer-events --from-beginning --max-messages 3 --timeout-ms 10000
Expect "JSON events with eventId, type TRANSFER_COMPLETED, amount, occurredAt"

Step "Consumer groups: two independent readers of the same topic"
dc exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:19092 --describe --all-groups
Expect "groups kirim-notifications (API) and kirim-risk (risk service), LAG 0"
