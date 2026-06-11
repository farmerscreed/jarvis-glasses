# BLE control-channel probe for AIMB-G2 glasses (WinRT, no external deps)
$ErrorActionPreference = 'Stop'

# Load the WinRT<->.NET interop extensions (provides System.WindowsRuntimeSystemExtensions)
$null = [System.Reflection.Assembly]::Load('System.Runtime.WindowsRuntime, Version=4.0.0.0, Culture=neutral, PublicKeyToken=b77a5c561934e089')

# --- WinRT async await helper for PS 5.1 ---
function Await($op, $resultType) {
  $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
  $g = $asTask.MakeGenericMethod($resultType)
  $t = $g.Invoke($null, @($op))
  $t.Wait(-1) | Out-Null
  $t.Result
}

# Load WinRT types
[void][Windows.Devices.Bluetooth.BluetoothLEDevice,Windows.Devices.Bluetooth,ContentType=WindowsRuntime]
[void][Windows.Devices.Bluetooth.GenericAttributeProfile.GattDeviceService,Windows.Devices.Bluetooth,ContentType=WindowsRuntime]
[void][Windows.Storage.Streams.DataReader,Windows.Storage.Streams,ContentType=WindowsRuntime]

$addr = [uint64]0x6393E18AA034
Write-Output "Connecting to AIMB-G2 at $("{0:X12}" -f $addr) ..."
$dev = Await ([Windows.Devices.Bluetooth.BluetoothLEDevice]::FromBluetoothAddressAsync($addr)) ([Windows.Devices.Bluetooth.BluetoothLEDevice])
if (-not $dev) { Write-Output "FAILED: could not get device object"; exit 1 }
Write-Output ("Device: {0}  ConnStatus: {1}" -f $dev.Name, $dev.ConnectionStatus)

$svcRes = Await ($dev.GetGattServicesAsync()) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattDeviceServicesResult])
Write-Output ("GATT services result status: {0}  count: {1}`n" -f $svcRes.Status, $svcRes.Services.Count)

foreach ($svc in $svcRes.Services) {
  Write-Output ("==== SERVICE {0} ====" -f $svc.Uuid)
  try {
    $chRes = Await ($svc.GetCharacteristicsAsync()) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattCharacteristicsResult])
    if ($chRes.Status -ne 'Success') { Write-Output ("  (characteristics status: {0})" -f $chRes.Status); continue }
    foreach ($ch in $chRes.Characteristics) {
      $props = $ch.CharacteristicProperties
      Write-Output ("  CHAR {0}  [{1}]" -f $ch.Uuid, $props)
      if ($props -band [Windows.Devices.Bluetooth.GenericAttributeProfile.GattCharacteristicProperties]::Read) {
        try {
          $rd = Await ($ch.ReadValueAsync()) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattReadResult])
          if ($rd.Status -eq 'Success' -and $rd.Value.Length -gt 0) {
            $reader = [Windows.Storage.Streams.DataReader]::FromBuffer($rd.Value)
            $bytes = New-Object byte[] $rd.Value.Length
            $reader.ReadBytes($bytes)
            $hex = ($bytes | ForEach-Object { '{0:X2}' -f $_ }) -join ' '
            $ascii = -join ($bytes | ForEach-Object { if ($_ -ge 32 -and $_ -lt 127) { [char]$_ } else { '.' } })
            Write-Output ("      value: {0}   |{1}|" -f $hex, $ascii)
          }
        } catch { Write-Output ("      (read failed: {0})" -f $_.Exception.Message) }
      }
    }
  } catch { Write-Output ("  (service error: {0})" -f $_.Exception.Message) }
  Write-Output ""
}
Write-Output "===== READABLE VALUES (decoded via CryptographicBuffer) ====="
[void][Windows.Security.Cryptography.CryptographicBuffer,Windows.Security.Cryptography,ContentType=WindowsRuntime]
$names = @{ '00002a00'='GAP Device Name'; '00002a25'='Serial Number'; '00002a27'='HW Revision';
 '00002a26'='Firmware Revision'; '00002a23'='System ID'; '00002a29'='Manufacturer';
 '00002a24'='Model Number'; '00002a28'='SW Revision'; '0000ae10'='AE10 config'; '0000fee3'='FEE3' }
foreach ($svc in $svcRes.Services) {
  $chRes = Await ($svc.GetCharacteristicsAsync()) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattCharacteristicsResult])
  if ($chRes.Status -ne 'Success') { continue }
  foreach ($ch in $chRes.Characteristics) {
    if ($ch.CharacteristicProperties -band [Windows.Devices.Bluetooth.GenericAttributeProfile.GattCharacteristicProperties]::Read) {
      $short = $ch.Uuid.ToString().Substring(0,8)
      $label = if ($names.ContainsKey($short)) { $names[$short] } else { $short }
      try {
        $rd = Await ($ch.ReadValueAsync()) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattReadResult])
        if ($rd.Status -eq 'Success') {
          $bytes = $null
          [Windows.Security.Cryptography.CryptographicBuffer]::CopyToByteArray($rd.Value, [ref]$bytes)
          if ($bytes -and $bytes.Length) {
            $hex = ($bytes | ForEach-Object { '{0:X2}' -f $_ }) -join ' '
            $ascii = -join ($bytes | ForEach-Object { if ($_ -ge 32 -and $_ -lt 127) { [char]$_ } else { '.' } })
            Write-Output ("  {0,-18}: '{1}'   [{2}]" -f $label, $ascii, $hex)
          } else { Write-Output ("  {0,-18}: (empty)" -f $label) }
        } else { Write-Output ("  {0,-18}: read status {1}" -f $label, $rd.Status) }
      } catch { Write-Output ("  {0,-18}: ERR {1}" -f $label, $_.Exception.Message) }
    }
  }
}
$dev.Dispose()
Write-Output "DONE."
