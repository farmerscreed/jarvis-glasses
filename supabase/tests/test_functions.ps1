# End-to-end test of the Edge Functions against the local stack.
# Prereq: keys filled into supabase/functions/.env AND functions served with that env:
#   supabase functions serve --env-file supabase/functions/.env --workdir <repo>
param(
  [string]$Base = "http://127.0.0.1:54421",
  [string]$Anon = "sb_publishable_ACJWlzQHlZjBrEguHvfOxg_3BJgxAaH",
  [string]$Email = "tester@local.dev",
  [string]$Password = "password123"
)
$ErrorActionPreference = "Stop"
$h = @{ apikey = $Anon; "Content-Type" = "application/json" }

function Get-Token {
  $body = @{ email = $Email; password = $Password } | ConvertTo-Json
  try {
    $r = Invoke-RestMethod -Method Post -Uri "$Base/auth/v1/signup" -Headers $h -Body $body
    if ($r.access_token) { return $r.access_token }
  } catch { }
  $r = Invoke-RestMethod -Method Post -Uri "$Base/auth/v1/token?grant_type=password" -Headers $h -Body $body
  return $r.access_token
}

$token = Get-Token
Write-Host ("got user token: {0}..." -f $token.Substring(0, 12))
$auth = @{ apikey = $Anon; Authorization = "Bearer $token"; "Content-Type" = "application/json" }

Write-Host "`n== INGEST =="
$mem = @(
  "I parked the car on level 3 of the Westfield garage, near the blue elevator.",
  "Sam said the Q3 budget is approved but marketing is capped at 50k.",
  "The wine I liked at dinner was a 2019 Barolo from Piedmont."
)
foreach ($t in $mem) {
  $b = @{ text = $t; type = "note" } | ConvertTo-Json
  $r = Invoke-RestMethod -Method Post -Uri "$Base/functions/v1/ingest" -Headers $auth -Body $b
  Write-Host ("  ingested {0}: {1}" -f $r.memory.id, $t.Substring(0, [Math]::Min(40, $t.Length)))
}

Write-Host "`n== RECALL 'where did I leave the car?' =="
$b = @{ query = "where did I leave the car?"; limit = 3 } | ConvertTo-Json
$r = Invoke-RestMethod -Method Post -Uri "$Base/functions/v1/recall" -Headers $auth -Body $b
$r.matches | ForEach-Object { Write-Host ("  [{0:N3}] {1}" -f $_.similarity, $_.text) }

Write-Host "`n== CHAT 'what did Sam say about the budget?' =="
$b = @{ message = "what did Sam say about the budget?" } | ConvertTo-Json
$r = Invoke-RestMethod -Method Post -Uri "$Base/functions/v1/chat" -Headers $auth -Body $b
Write-Host ("  ANSWER: {0}" -f $r.answer)

Write-Host "`nDONE"
