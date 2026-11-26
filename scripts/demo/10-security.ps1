# Kirim demo, feature 10: authentication (Keycloak), authorization and the reverse proxy.
# Run 01-setup.ps1 first. Read-only: nothing here moves money.

function Show-Call([string]$Label, [scriptblock]$Call) {
  try {
    $r = & $Call
    "{0,-58} {1}" -f $Label, "200/201 OK"
  } catch {
    $code = [int]$_.Exception.Response.StatusCode
    $problem = $null
    if ($_.ErrorDetails -and $_.ErrorDetails.Message) { try { $problem = $_.ErrorDetails.Message | ConvertFrom-Json } catch { } }
    "{0,-58} {1} {2}" -f $Label, $code, $problem.code
  }
}

Step "What is inside alice's access token"
$claims = Read-Jwt (Get-KirimToken alice)
[pscustomobject]@{
  iss = $claims.iss; sub = $claims.sub; preferred_username = $claims.preferred_username
  roles = ($claims.realm_access.roles -join ", ")
  expires = [DateTimeOffset]::FromUnixTimeSeconds($claims.exp).LocalDateTime
} | Format-List
Expect "iss = $KirimUrl/auth/realms/kirim, sub = alice's Keycloak id (= ownerId of her accounts), roles include customer"

Step "Authentication: no token, a broken token"
Show-Call "GET /accounts without a token" { Invoke-RestMethod "$api/accounts" }
Show-Call "GET /accounts with a tampered token" {
  Invoke-RestMethod "$api/accounts" -Headers @{ Authorization = "Bearer $((Get-KirimToken alice))x" }
}
Expect "401 UNAUTHENTICATED twice: the signature is checked with Keycloak's public key"

Step "Authorization: whose data you can see"
Show-Call "alice lists her accounts" { Invoke-Kirim "$api/accounts" -As alice }
$mine = Invoke-Kirim "$api/accounts" -As alice
$all  = Invoke-Kirim "$api/accounts" -As ops
"   alice sees $($mine.Count) account(s); ops sees $($all.Count)"
Show-Call "alice reads Bob's account" { Invoke-Kirim "$api/accounts/$($bob.id)" -As alice }
Show-Call "ops reads Bob's account" { Invoke-Kirim "$api/accounts/$($bob.id)" -As ops }
Expect "alice only her own; Bob's account is 404 for alice (ids cannot be probed), 200 for ops"

Step "Authorization: whose money you can move"
Send-Transfer $bob.id $alice.id 1000 -As alice | Format-Table Http, Code, Detail -AutoSize
Send-Transfer $bob.id $alice.id 1000 -As ops   | Format-Table Http, Code, Detail -AutoSize
Expect "404 for alice (not her account), 403 ACCOUNT_NOT_OWNED for ops (may look, may not move customer money)"

Step "Role checks: OPS-only endpoints"
Show-Call "alice runs a reconciliation" { Invoke-Kirim -Method Post "$api/reconciliations" -As alice }
Show-Call "alice reads risk alerts" { Invoke-Kirim "$risk/alerts" -As alice }
Show-Call "ops reads risk alerts" { Invoke-Kirim "$risk/alerts" -As ops }
Expect "403 FORBIDDEN, 403 FORBIDDEN, 200"

Step "Reverse proxy: one entry point"
$h = (Invoke-WebRequest -UseBasicParsing "$api/accounts" -Headers (Get-AuthHeader alice)).Headers
"X-Request-Id: $(@($h['X-Request-Id']) -join '')   X-Frame-Options: $(@($h['X-Frame-Options']) -join '')"
Show-Call "GET $KirimUrl/actuator/prometheus (through the proxy)" { Invoke-WebRequest -UseBasicParsing "$KirimUrl/actuator/prometheus" }
Show-Call "GET $KirimUrl/risk/actuator/health (through the proxy)" { Invoke-WebRequest -UseBasicParsing "$KirimUrl/risk/actuator/health" }
Expect "every response carries an X-Request-Id; actuator is 404 from outside (internal only)"
"   web app: $KirimUrl   Keycloak admin: $KirimUrl/auth/admin (admin / admin)   Swagger: $KirimUrl/swagger-ui.html"
