# Kirim demo, step 1: helpers + fresh demo accounts (feature 1: opening accounts).
#
# Dot-source it (note the leading ". ") in every new PowerShell window, from the repo root:
#   . .\scripts\demo\01-setup.ps1
# Works in Windows PowerShell 5.1 and PowerShell 7. Needs `docker compose up -d` running.

# No progress bars: they clutter the output and make Invoke-WebRequest much slower in Windows PowerShell 5.1.
$ProgressPreference = "SilentlyContinue"

$KirimRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent   # scripts/demo -> repo root
$compose   = Join-Path $KirimRoot "docker-compose.yml"

# Host ports: the same .env that docker compose reads (API_PORT), else the defaults.
function Get-KirimPort([string]$Name, [int]$Default) {
  $envFile = Join-Path $KirimRoot ".env"
  if (Test-Path $envFile) {
    $m = Select-String -Path $envFile -Pattern "^\s*$Name\s*=\s*(\d+)\s*$" | Select-Object -First 1
    if ($m) { return [int]$m.Matches[0].Groups[1].Value }
  }
  return $Default
}
$ApiPort  = Get-KirimPort "API_PORT" 8080
$RiskPort = 8081
$api  = "http://localhost:$ApiPort/api/v1"    # transfer API (Kotlin)
$risk = "http://localhost:$RiskPort/api/v1"   # risk service (Java)

# Unique per run, so fixed idempotency keys never collide with an earlier run (that would be a 422).
$run = Get-Date -Format "yyyyMMdd-HHmmss"

# `dc ...` = `docker compose ...` for Kirim, from any folder.
function dc { docker compose -f $compose @args }

function Step([string]$Text) { Write-Host ""; Write-Host "== $Text" -ForegroundColor Cyan }
function Expect([string]$Text) { Write-Host "   expected: $Text" -ForegroundColor DarkGray }

# POST /transfers and return one row: the result, also for 4xx/5xx.
function Send-Transfer {
  param([string]$From, [string]$To, [long]$Amount,
        [string]$Key = [guid]::NewGuid().ToString(), [string]$Description = "demo")
  $body = @{ fromAccountId = $From; toAccountId = $To; amount = $Amount; description = $Description } | ConvertTo-Json
  try {
    $r = Invoke-WebRequest -UseBasicParsing -Method Post "$api/transfers" -ContentType "application/json" `
           -Headers @{ "Idempotency-Key" = $Key } -Body $body
    $t = $r.Content | ConvertFrom-Json
    [pscustomobject]@{ Http = [int]$r.StatusCode; Replayed = (@($r.Headers["Idempotent-Replayed"]) -join "")
                       Status = $t.status; Reason = $t.failureReason; Code = $null; RetryAfter = $null
                       Amount = $t.amount; Id = $t.id; Key = $Key }
  } catch {
    $resp = $_.Exception.Response
    if (-not $resp) { throw }   # no HTTP answer at all: API down or wrong port
    $retry = $null
    if ($resp.Headers.RetryAfter) { $retry = [int]$resp.Headers.RetryAfter.Delta.TotalSeconds }   # PowerShell 7
    elseif ($resp.Headers -is [System.Net.WebHeaderCollection]) { $retry = $resp.Headers["Retry-After"] }   # 5.1
    $problem = $null
    if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
      try { $problem = $_.ErrorDetails.Message | ConvertFrom-Json } catch { }
    }
    [pscustomobject]@{ Http = [int]$resp.StatusCode; Replayed = ""; Status = $null; Reason = $null
                       Code = $problem.code; RetryAfter = $retry; Amount = $Amount; Id = $null; Key = $Key
                       Detail = $problem.detail }
  }
}

function Open-Account([string]$Name, [long]$Balance = 0) {
  $body = @{ ownerName = $Name; openingBalance = $Balance } | ConvertTo-Json
  Invoke-RestMethod -Method Post "$api/accounts" -ContentType "application/json" -Body $body
}

function Get-Balance($Account) { (Invoke-RestMethod "$api/accounts/$($Account.id)").balance }

# Lines of a Prometheus endpoint that match a pattern.
function Get-Metric([string]$Pattern, [int]$Port = $ApiPort) {
  (Invoke-WebRequest -UseBasicParsing "http://localhost:$Port/actuator/prometheus").Content -split "`n" |
    Where-Object { $_ -match $Pattern -and $_ -notmatch "^#" }
}

# Waits until both services answer their readiness probe.
function Wait-KirimReady([int]$TimeoutSeconds = 90) {
  foreach ($port in $ApiPort, $RiskPort) {
    $url = "http://localhost:$port/actuator/health/readiness"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ($true) {
      $status = $null
      try { $status = (Invoke-RestMethod $url -TimeoutSec 5).status } catch { }
      if ($status -eq "UP") { Write-Host "   UP  $url"; break }
      if ((Get-Date) -gt $deadline) {
        throw "Not ready after $TimeoutSeconds s: $url. Is 'docker compose up -d' running? See scripts/demo/README.md"
      }
      Start-Sleep 3
    }
  }
}

# Starts a demo step at the top of a minute when we are late in the current one,
# so a burst of transfers stays inside one rate-limit window.
function Wait-FreshMinute([int]$LatestSecond = 40) {
  $s = (Get-Date).Second
  if ($s -gt $LatestSecond) {
    $wait = 61 - $s
    Write-Host "   waiting $wait s for a fresh rate-limit window..." -ForegroundColor DarkGray
    Start-Sleep $wait
  }
}

Step "Kirim at $KirimRoot (API port $ApiPort, risk service port $RiskPort)"
Wait-KirimReady

Step "Opening demo accounts (run $run)"
$alice = Open-Account "Alice $run" 200000000   # Rp 200 juta: idempotency, risk alerts
$bob   = Open-Account "Bob $run"               # receives everything
$carol = Open-Account "Carol $run" 1000000     # Rp 1 juta: rate limiting
$alice, $bob, $carol | Format-Table id, ownerName, currency, balance -AutoSize
Expect "three accounts in IDR; Alice 200000000, Bob 0, Carol 1000000"
