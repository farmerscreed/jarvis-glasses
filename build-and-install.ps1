# JARVIS - build the app and install it on the connected Pixel. Double-click BUILD-JARVIS.bat to run.
# Writes everything to build.log next to this script so Claude can read the result.
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$log = Join-Path $root "build.log"
"=== JARVIS build $(Get-Date -Format s) on $env:COMPUTERNAME as $env:USERNAME ===" | Out-File $log -Encoding utf8
function Log($m) { Write-Host $m; Add-Content -Path $log -Value $m -Encoding utf8 }

# 1. Android SDK
$sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk", "C:\Users\admin\AppData\Local\Android\Sdk", "D:\Android\Sdk") | Where-Object { $_ -and (Test-Path (Join-Path $_ "platforms")) }
$sdk = $sdkCandidates | Select-Object -First 1
Log "SDK: $sdk"
if (-not $sdk) { Log "ERROR: Android SDK not found on this PC."; Read-Host "Press Enter to close"; exit 1 }
$lp = Join-Path $root "android\local.properties"
$sdkFwd = $sdk -replace '\\','/'
$lines = Get-Content $lp | Where-Object { $_ -notmatch '^sdk\.dir=' }
@("sdk.dir=$sdkFwd") + $lines | Set-Content $lp -Encoding ascii
Log "local.properties sdk.dir -> $sdkFwd"

# 2. Java 17+
$javaCandidates = @($env:JAVA_HOME, "C:\Program Files\Android\Android Studio\jbr", "$env:LOCALAPPDATA\Programs\Android Studio\jbr") + (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue | ForEach-Object FullName) | Where-Object { $_ -and (Test-Path (Join-Path $_ "bin\java.exe")) }
$jh = $javaCandidates | Select-Object -First 1
Log "JAVA_HOME: $jh"
if (-not $jh) { Log "ERROR: no JDK found (need Android Studio or JDK 17)."; Read-Host "Press Enter to close"; exit 1 }
$env:JAVA_HOME = $jh
& "$jh\bin\java.exe" -version 2>&1 | ForEach-Object { Log "$_" }

# 3. Build the prod (cloud) app
Set-Location (Join-Path $root "android")
Log "--- gradle clean + :app:assembleProdDebug (no build cache: old outputs point at the previous C: folder) ---"
& .\gradlew.bat --no-daemon --no-build-cache clean :app:assembleProdDebug 2>&1 | ForEach-Object { Log "$_" }
$apk = Join-Path $root "android\app\build\outputs\apk\prod\debug\app-prod-debug.apk"
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $apk)) { Log "BUILD FAILED (exit $LASTEXITCODE)"; Read-Host "Build failed - press Enter to close"; exit 1 }
Log "BUILD OK: $apk ($((Get-Item $apk).LastWriteTime))"

# 4. Install on the phone (USB debugging on, phone unlocked)
$adb = Join-Path $sdk "platform-tools\adb.exe"
& $adb devices 2>&1 | ForEach-Object { Log "$_" }
& $adb install -r $apk 2>&1 | ForEach-Object { Log "$_" }
if ($LASTEXITCODE -eq 0) { Log "INSTALL OK"; & $adb shell monkey -p com.echo.companion -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null }
else { Log "INSTALL FAILED - is the Pixel plugged in by USB with USB debugging allowed?" }
Log "=== done $(Get-Date -Format s) ==="
Read-Host "Done - press Enter to close"
