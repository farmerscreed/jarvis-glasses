# Pull JARVIS logs from the phone over the wireless adb link into phone.log
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $root ("phone-" + (Get-Date -Format "HHmmss") + ".log")
$sdk = @($env:ANDROID_HOME, "$env:LOCALAPPDATA\Android\Sdk", "C:\Users\admin\AppData\Local\Android\Sdk") | Where-Object { $_ -and (Test-Path (Join-Path $_ "platform-tools\adb.exe")) } | Select-Object -First 1
$adb = Join-Path $sdk "platform-tools\adb.exe"
"=== phone logs $(Get-Date -Format s) ===" | Out-File $out -Encoding utf8
$svc = & $adb mdns services 2>&1
$m = $svc | Select-String -Pattern '_adb-tls-connect\._tcp\.?\s+(\d+\.\d+\.\d+\.\d+:\d+)'
$cfg = Join-Path $root "wifi.txt"
$fromFile = if (Test-Path $cfg) { (Get-Content $cfg | Where-Object { $_ -match '^connect=' } | Select-Object -First 1) -replace '^connect=', '' } else { "" }
$target = if ($m) { $m.Matches[0].Groups[1].Value } elseif ($fromFile) { $fromFile.Trim() } else { "192.168.0.115:44639" }
& $adb connect $target 2>&1 | Out-File $out -Append -Encoding utf8
& $adb devices -l 2>&1 | Out-File $out -Append -Encoding utf8
& $adb -s $target logcat -d -v time -s EchoVision:V EchoTranslate:V EchoLatency:V EchoMedia:V EchoPhrase:V EchoP2p:V EchoReactor:V EchoBle:I SherpaStt:V AndroidRuntime:E 2>&1 | Out-File $out -Append -Encoding utf8
"=== translator debug log (files/translate-debug.log) ===" | Out-File $out -Append -Encoding utf8
& $adb -s $target shell run-as com.echo.companion cat files/translate-debug.log.1 2>$null | Out-File $out -Append -Encoding utf8
& $adb -s $target shell run-as com.echo.companion cat files/translate-debug.log 2>&1 | Out-File $out -Append -Encoding utf8
"=== voice model files on phone ===" | Out-File $out -Append -Encoding utf8
& $adb -s $target shell run-as com.echo.companion ls -l files/stt 2>&1 | Out-File $out -Append -Encoding utf8
"=== done ===" | Out-File $out -Append -Encoding utf8
