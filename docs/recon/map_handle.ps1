# Map ATT handles -> UUIDs from GATT discovery responses in the snoop log
$path = "C:\Users\admin\Documents\APP\Jarvis Glasses\btsnoop_hci.log"
$b = [System.IO.File]::ReadAllBytes($path)
function BE32($a,$o){ ([int]$a[$o] -shl 24) -bor ([int]$a[$o+1] -shl 16) -bor ([int]$a[$o+2] -shl 8) -bor [int]$a[$o+3] }
function U16($a,$o){ [int]$a[$o] -bor ([int]$a[$o+1] -shl 8) }
function UuidStr($a,$o,$len){
  if($len -eq 2){ '0x{0:X4}' -f (U16 $a $o) }
  else { # 16-byte little-endian
    $bytes = $a[$o..($o+15)]; [array]::Reverse($bytes)
    (($bytes | ForEach-Object {'{0:X2}' -f $_}) -join '') }
}
$pos = 16
$opcounts = @{}
while ($pos + 24 -le $b.Length) {
  $incl = BE32 $b ($pos+4); $dStart = $pos + 24
  if ($dStart + $incl -gt $b.Length) { break }
  $d = $b[$dStart..($dStart+$incl-1)]
  if ($d.Length -ge 10 -and $d[0] -eq 0x02) {
    $cid = U16 $d 7
    if ($cid -eq 4) {
      $op = $d[9]
      $opcounts[$op] = 1 + ($opcounts[$op] | ForEach-Object {$_}); if(-not $opcounts.ContainsKey($op)){}
      $p = $d[9..($d.Length-1)]  # ATT PDU
      if ($op -eq 0x09) { # Read By Type Response: [op][eachlen][entries]
        $el = $p[1]; $i = 2
        while ($i + $el -le $p.Length) {
          $declH = U16 $p $i; $props = $p[$i+2]; $valH = U16 $p ($i+3)
          $uuidLen = $el - 5
          $uuid = UuidStr $p ($i+5) $uuidLen
          Write-Output ("CHAR declH=0x{0:X4} valH=0x{1:X4} props=0x{2:X2} uuid={3}" -f $declH,$valH,$props,$uuid)
          $i += $el
        }
      }
      elseif ($op -eq 0x05) { # Find Information Response: [op][format][handle+uuid...]
        $fmt = $p[1]; $ul = if($fmt -eq 1){2}else{16}; $i = 2
        while ($i + 2 + $ul -le $p.Length) {
          $h = U16 $p $i; $uuid = UuidStr $p ($i+2) $ul
          Write-Output ("DESC handle=0x{0:X4} uuid={1}" -f $h,$uuid)
          $i += 2 + $ul
        }
      }
    }
  }
  $pos = $dStart + $incl
}
Write-Output "`n===== ATT opcode counts ====="
$opcounts.GetEnumerator() | Sort-Object Name | ForEach-Object { '0x{0:X2}: {1}' -f $_.Name,$_.Value }
