# Kirim demo, feature 7: transfers keep working while Redis is down (fail open). DISRUPTIVE: stops Redis.
# Run 01-setup.ps1 and 06-cache-metrics.ps1 first. Redis is started again at the end, even if something fails.

try {
  Step "Stopping Redis"
  dc stop redis

  Step "A transfer while Redis is down"
  Send-Transfer $bob.id $alice.id 1000 | Format-Table Http, Status -AutoSize
  Expect "201 COMPLETED (maybe ~0.5 s slower: the Redis timeout)"

  if ($t) {
    Step "Reading a transfer falls back to PostgreSQL"
    Invoke-Kirim "$api/transfers/$($t.Id)" -As bob | Format-List id, status
  }

  Step "Health"
  "readiness: $((Invoke-RestMethod "http://localhost:$ApiPort/actuator/health/readiness").status)"
  Expect "UP: readiness depends on PostgreSQL only, so Kubernetes keeps routing traffic"
  try { "overall:   $((Invoke-RestMethod "http://localhost:$ApiPort/actuator/health").status)" }
  catch { "overall:   DOWN (HTTP $([int]$_.Exception.Response.StatusCode))" }
  Expect "DOWN (HTTP 503): the overall health still reports Redis, for monitoring"

  Step "What the API logged"
  dc logs api --tail 30 | Select-String "Redis unavailable"
  Get-Metric 'kirim_ratelimit_total.*error'
} finally {
  Step "Starting Redis again"
  dc start redis
}
