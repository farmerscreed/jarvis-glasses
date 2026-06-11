# Minimal btsnoop parser: extract ATT writes (0x52/0x12) and notifications (0x1b/0x1d)
$path = "C:\Users\admin\Documents\APP\Jarvis Glasses\btsnoop_hci.log"
$b = [System.IO.File]::ReadAllBytes($path)
function BE32($a,$o){ ([int]$a[$o] -shl 24) -bor ([int]$a[$o+1] -shl 16) -bor ([int]$a[$o+2] -shl 8) -bor [int]$a[$o+3] }
function BE64($a,$o){ $v=[uint64]0; for($i=0;$i -lt 8;$i++){ $v=($v -shl 8) -bor [uint64]$a[$o+$i] }; $v }

# File header: 8 magic + 4 version + 4 datalink
$datalink = BE32 $b 12
Write-Output ("datalink type: {0}" -f $datalink)
$pos = 16
$first = $null
$rows = @()
while ($pos + 24 -le $b.Length) {
  $incl = BE32 $b ($pos+4)
  $flags = BE32 $b ($pos+8)
  $ts = BE64 $b ($pos+16)
  $dStart = $pos + 24
  if ($dStart + $incl -gt $b.Length) { break }
  if ($first -eq $null) { $first = $ts }
  $rel = [math]::Round(($ts - $first)/1e6, 3)
  $d = $b[$dStart..($dStart+$incl-1)]
  # H4: first byte is packet type (datalink 1002). ACL = 0x02
  # H4 ACL frame: d0=type(0x02) d1-2=handle d3-4=acllen d5-6=l2caplen d7-8=cid d9=ATTop d10-11=attHandle d12+=val
  if ($d.Length -ge 1 -and $d[0] -eq 0x02 -and $d.Length -ge 10) {
    $cid = [int]$d[7] -bor ([int]$d[8] -shl 8)
    if ($cid -eq 4) {  # ATT
      $op = $d[9]
      if ($op -in 0x52,0x12,0x1b,0x1d,0x1e,0x13) {
        $ah = if ($d.Length -ge 12) { '0x{0:X4}' -f ([int]$d[10] -bor ([int]$d[11] -shl 8)) } else { '?' }
        $val = ''
        if ($d.Length -ge 13) { $val = ($d[12..($d.Length-1)] | ForEach-Object { '{0:X2}' -f $_ }) -join ' ' }
        $dir = if ($op -in 0x1b,0x1d) { 'DEV->APP (notify)' } else { 'APP->DEV (write)' }
        $opn = switch($op){ 0x52{'WriteCmd'} 0x12{'WriteReq'} 0x13{'WriteRsp'} 0x1b{'Notify'} 0x1d{'Indicate'} 0x1e{'IndConf'} default{('0x{0:X2}' -f $op)} }
        $rows += [PSCustomObject]@{ t=$rel; dir=$dir; op=$opn; handle=$ah; len=($d.Length-12); value=$val }
      }
    }
  }
  $pos = $dStart + $incl
}
Write-Output ("total ATT write/notify packets: {0}`n" -f $rows.Count)
Write-Output "===== APP->DEV WRITES (the trigger candidates) ====="
$rows | Where-Object { $_.op -in 'WriteCmd','WriteReq' } | Format-Table t,handle,op,len,value -AutoSize -Wrap
Write-Output "`n===== Write frequency by (handle,value) — the trigger should appear ~3x ====="
$rows | Where-Object { $_.op -in 'WriteCmd','WriteReq' } | Group-Object handle,value | Sort-Object Count -Descending | Select-Object Count,Name | Format-Table -AutoSize -Wrap
