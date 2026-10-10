# Wireless install. Reads wifi.txt:  connect=IP:PORT   [pair=IP:PORT  code=123456]
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$log = Join-Path $root "install.log"
"=== JARVIS wifi install $(Get-Date -Format s) ===" | Out-File $log -Encoding utf8
function Log($m) { Write-Host $m; Add-Content -Path $log -Value $m -Encoding utf8 }
$cfg = @{}; Get-Content (Join-Path $root "wifi.txt") | ForEach-Object { if ($_ -match '^\s*(\w+)\s*=\s*(\S+)') { $cfg[$matches[1]] = $matches[2] } }
$sdk = @($env:ANDROID_HOME, "$env:LOCALAPPDATA\Android\Sdk", "C:\Users\admin\AppData\Local\Android\Sdk") | Where-Object { $_ -and (Test-Path (Join-Path $_ "platform-tools\adb.exe")) } | Select-Object -First 1
$adb = Join-Path $sdk "platform-tools\adb.exe"
$apk = Join-Path $root "android\app\build\outputs\apk\prod\debug\app-prod-debug.apk"
& $adb version 2>&1 | Select-Object -First 1 | ForEach-Object { Log "$_" }
if ($cfg.code -and -not $cfg.pair) {
  Log "looking for the phone's pairing port (mDNS)..."
  for ($i = 0; $i -lt 10 -and -not $cfg.pair; $i++) {
    $svc = & $adb mdns services 2>&1
    $svc | ForEach-Object { Log "$_" }
    $m = $svc | Select-String -Pattern '_adb-tls-pairing\._tcp\.?\s+(\d+\.\d+\.\d+\.\d+:\d+)'
    if ($m) { $cfg.pair = $m.Matches[0].Groups[1].Value } else { Start-Sleep -Seconds 1 }
  }
}
if ($cfg.pair -and $cfg.code) { Log "pairing $($cfg.pair)"; & $adb pair $cfg.pair $cfg.code 2>&1 | ForEach-Object { Log "$_" } }
# The phone picks a new port each time Wireless debugging is switched on - discover it (up to ~20 s).
for ($j = 0; $j -lt 20; $j++) {
  $svc2 = & $adb mdns services 2>&1
  $m2 = $svc2 | Select-String -Pattern '_adb-tls-connect\._tcp\.?\s+(\d+\.\d+\.\d+\.\d+:\d+)'
  if ($m2) { $cfg.connect = $m2.Matches[0].Groups[1].Value; Log "connect port from mDNS: $($cfg.connect)"; break }
  Start-Sleep -Seconds 1
}
if (-not $m2) { Log "mDNS did not find the phone; trying the saved address"; $svc2 | ForEach-Object { Log "$_" } }
if ($cfg.connect) { Log "connecting $($cfg.connect)"; & $adb connect $cfg.connect 2>&1 | ForEach-Object { Log "$_" } }
Start-Sleep -Seconds 2
& $adb devices -l 2>&1 | ForEach-Object { Log "$_" }
Log "installing (268 MB over Wi-Fi - may take 1-3 minutes)..."
& $adb -s $cfg.connect install -r $apk 2>&1 | ForEach-Object { Log "$_" }
if ($LASTEXITCODE -eq 0) { Log "INSTALL OK"; & $adb -s $cfg.connect shell monkey -p com.echo.companion -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null }
else { Log "INSTALL FAILED" }
Log "=== done ==="
Start-Sleep -Seconds 5
