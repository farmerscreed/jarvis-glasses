# Install the already-built JARVIS app on the USB-connected phone. Logs to install.log.
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$log = Join-Path $root "install.log"
"=== JARVIS install $(Get-Date -Format s) ===" | Out-File $log -Encoding utf8
function Log($m) { Write-Host $m; Add-Content -Path $log -Value $m -Encoding utf8 }
$sdk = @($env:ANDROID_HOME, "$env:LOCALAPPDATA\Android\Sdk", "C:\Users\admin\AppData\Local\Android\Sdk") | Where-Object { $_ -and (Test-Path (Join-Path $_ "platform-tools\adb.exe")) } | Select-Object -First 1
$adb = Join-Path $sdk "platform-tools\adb.exe"
$apk = Join-Path $root "android\app\build\outputs\apk\prod\debug\app-prod-debug.apk"
& $adb kill-server 2>&1 | Out-Null
& $adb start-server 2>&1 | ForEach-Object { Log "$_" }
Log "Waiting up to 60s for the phone (unlock it and tap 'Allow USB debugging' if asked)..."
for ($i = 0; $i -lt 30; $i++) {
  $devs = & $adb devices 2>&1
  if ($devs -match "`tdevice") { break }
  Start-Sleep -Seconds 2
}
& $adb devices -l 2>&1 | ForEach-Object { Log "$_" }
& $adb install -r $apk 2>&1 | ForEach-Object { Log "$_" }
if ($LASTEXITCODE -eq 0) { Log "INSTALL OK"; & $adb shell monkey -p com.echo.companion -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null }
else { Log "INSTALL FAILED" }
Log "=== done ==="
Start-Sleep -Seconds 5
