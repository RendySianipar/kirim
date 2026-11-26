# Kirim demo, step 1: helpers, login + fresh demo accounts (feature 1: opening accounts).
#
# Dot-source it (note the leading ". ") in every new PowerShell window, from the repo root:
#   . .\scripts\demo\01-setup.ps1
# Works in Windows PowerShell 5.1 and PowerShell 7. Needs `docker compose up -d` running.
#
# Requests go through the reverse proxy (http://localhost) with a Keycloak token
# for the account's owner: alice, bob, carol, dave (customers) or ops. Their
# passwords equal their usernames (development realm, deploy/keycloak).
# To call an API you are debugging in IntelliJ directly, set this first:
#   $env:KIRIM_API_URL = 'http://localhost:8090'

# No progress bars: they clutter the output and make Invoke-WebRequest much slower in Windows PowerShell 5.1.
$ProgressPreference = "SilentlyContinue"

$KirimRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent   # scripts/demo -> repo root
$compose   = Join-Path $KirimRoot "docker-compose.yml"

# Host ports: the same .env that docker compose reads, else the defaults.
function Get-KirimPort([string]$Name, [int]$Default) {
  $envFile = Join-Path $KirimRoot ".env"
  if (Test-Path $envFile) {
    $m = Select-String -Path $envFile -Pattern "^\s*$Name\s*=\s*(\d+)\s*$" | Select-Object -First 1
    if ($m) { return [int]$m.Matches[0].Groups[1].Value }
  }
  return $Default
}
$ApiPort   = Get-KirimPort "API_PORT" 8080      # direct, for actuator (health, metrics) only
$RiskPort  = 8081
$ProxyPort = Get-KirimPort "PROXY_PORT" 80
$KirimUrl  = if ($ProxyPort -eq 80) { "http://localhost" } else { "http://localhost:$ProxyPort" }
$api  = if ($env:KIRIM_API_URL) { "$($env:KIRIM_API_URL)/api/v1" } else { "$KirimUrl/api/v1" }   # transfer API (Kotlin)
$risk = "$KirimUrl/risk/api/v1"                                                                  # risk service (Java)
$tokenUrl = "$KirimUrl/auth/realms/kirim/protocol/openid-connect/token"

# Unique per run, so fixed idempotency keys never collide with an earlier run (that would be a 422).
$run = Get-Date -Format "yyyyMMdd-HHmmss"

# `dc ...` = `docker compose ...` for Kirim, from any folder.
function dc { docker compose -f $compose @args }

function Step([string]$Text) { Write-Host ""; Write-Host "== $Text" -ForegroundColor Cyan }
function Expect([string]$Text) { Write-Host "   expected: $Text" -ForegroundColor DarkGray }

# ---- authentication

# An access token from Keycloak for a demo user (password grant with the
# kirim-cli client: fine for scripts on a dev realm, never for real users).
# Cached per user and fetched again shortly before it expires (5 minutes).
if (-not $global:KirimTokens) { $global:KirimTokens = @{} }
function Get-KirimToken([string]$User) {
  $cached = $global:KirimTokens[$User]
  if ($cached -and $cached.ExpiresAt -gt (Get-Date).AddSeconds(30)) { return $cached.Token }
  $r = Invoke-RestMethod -Method Post $tokenUrl -Body @{
    grant_type = "password"; client_id = "kirim-cli"; username = $User; password = $User; scope = "openid"
  }
  $global:KirimTokens[$User] = @{ Token = $r.access_token; ExpiresAt = (Get-Date).AddSeconds([int]$r.expires_in) }
  return $r.access_token
}
function Get-AuthHeader([string]$User) { @{ Authorization = "Bearer $(Get-KirimToken $User)" } }

# The payload of a JWT, decoded (base64url), to look at its claims.
function Read-Jwt([string]$Token) {
  $p = $Token.Split(".")[1].Replace("-", "+").Replace("_", "/")
  switch ($p.Length % 4) { 2 { $p += "==" } 3 { $p += "=" } }
  [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p)) | ConvertFrom-Json
}

# Which demo user owns which account, so Send-Transfer can use the owner's token.
if (-not $global:KirimOwners) { $global:KirimOwners = @{} }

# GET (or POST) an API URL as a demo user.
function Invoke-Kirim {
  param([Parameter(Mandatory)][string]$Url, [Parameter(Mandatory)][string]$As, [string]$Method = "Get")
  Invoke-RestMethod -Method $Method $Url -Headers (Get-AuthHeader $As)
}

# ---- API helpers

# POST /transfers and return one row: the result, also for 4xx/5xx.
# Sent as the owner of the From account, unless -As names someone else.
function Send-Transfer {
  param([string]$From, [string]$To, [long]$Amount,
        [string]$Key = [guid]::NewGuid().ToString(), [string]$Description = "demo", [string]$As)
  if (-not $As) { $As = $global:KirimOwners[$From] }
  if (-not $As) { $As = "alice" }
  $body = @{ fromAccountId = $From; toAccountId = $To; amount = $Amount; description = $Description } | ConvertTo-Json
  $headers = Get-AuthHeader $As
  $headers["Idempotency-Key"] = $Key
  try {
    $r = Invoke-WebRequest -UseBasicParsing -Method Post "$api/transfers" -ContentType "application/json" `
           -Headers $headers -Body $body
    $t = $r.Content | ConvertFrom-Json
    [pscustomobject]@{ Http = [int]$r.StatusCode; Replayed = (@($r.Headers["Idempotent-Replayed"]) -join "")
                       Status = $t.status; Reason = $t.failureReason; Code = $null; RetryAfter = $null
                       Amount = $t.amount; Id = $t.id; Key = $Key }
  } catch {
    $resp = $_.Exception.Response
    if (-not $resp) { throw }   # no HTTP answer at all: stack down or wrong port
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

# Opens an account owned by -As (the owner comes from the token, never from the body).
function Open-Account([string]$Name, [long]$Balance = 0, [Parameter(Mandatory)][string]$As) {
  $body = @{ ownerName = $Name; openingBalance = $Balance } | ConvertTo-Json
  $a = Invoke-RestMethod -Method Post "$api/accounts" -ContentType "application/json" -Body $body -Headers (Get-AuthHeader $As)
  $global:KirimOwners[$a.id] = $As
  $a
}

function Get-Balance($Account) { (Invoke-Kirim "$api/accounts/$($Account.id)" -As $global:KirimOwners[$Account.id]).balance }

# Lines of a Prometheus endpoint that match a pattern. Actuator is not exposed
# through the proxy, so this goes to the service's own port.
function Get-Metric([string]$Pattern, [int]$Port = $ApiPort) {
  (Invoke-WebRequest -UseBasicParsing "http://localhost:$Port/actuator/prometheus").Content -split "`n" |
    Where-Object { $_ -match $Pattern -and $_ -notmatch "^#" }
}

# Waits until both services answer their readiness probe and Keycloak serves the realm.
function Wait-KirimReady([int]$TimeoutSeconds = 120) {
  $urls = @("http://localhost:$ApiPort/actuator/health/readiness",
            "http://localhost:$RiskPort/actuator/health/readiness",
            "$KirimUrl/auth/realms/kirim")
  foreach ($url in $urls) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ($true) {
      $status = $null
      try {
        $r = Invoke-RestMethod $url -TimeoutSec 5
        $status = if ($r.realm) { "UP" } else { $r.status }   # the realm URL answers with its name
      } catch { }
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

Step "Kirim at $KirimRoot (entry point $KirimUrl, API $api)"
Wait-KirimReady

Step "Logging in (Keycloak password grant): alice, bob, carol, ops"
"alice", "bob", "carol", "ops" | ForEach-Object { [void](Get-KirimToken $_); "   token for $_" }

Step "Opening demo accounts (run $run), each owned by its user"
$alice = Open-Account "Alice $run" 200000000 -As alice   # Rp 200 juta: idempotency, risk alerts
$bob   = Open-Account "Bob $run" -As bob                 # receives everything
$carol = Open-Account "Carol $run" 1000000 -As carol     # Rp 1 juta: rate limiting
$alice, $bob, $carol | Format-Table id, ownerName, currency, balance, ownerId -AutoSize
Expect "three accounts in IDR; Alice 200000000, Bob 0, Carol 1000000; ownerId = each user's Keycloak id"
