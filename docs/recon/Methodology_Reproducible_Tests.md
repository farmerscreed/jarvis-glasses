# Methodology — How Every Test Was Done (Reproducible)

> Companion to `00_HANDOFF_START_HERE.md`. This file documents the **exact tools, commands, and reasoning** for each test performed, so any session can reproduce or extend them. Windows 11 / PowerShell 5.1. Paths are absolute.
> `$adb = "C:\Users\admin\AppData\Local\Android\Sdk\platform-tools\adb.exe"` (assumed below).

---

## A. BLE GATT enumeration (glasses ↔ PC, via WinRT)

**Goal:** list the glasses' BLE services + characteristics + properties. **Used in:** Sessions 01–02.
**Precondition:** glasses Bluetooth-paired to the **PC** (single-point — disconnect from phone first).

**How:** Windows has native WinRT Bluetooth-LE APIs callable from PowerShell — no install. Script `ble_probe.ps1` does it. Key mechanics that took iteration:
- Must load the interop assembly or `AsTask`/await fails:
  `[System.Reflection.Assembly]::Load('System.Runtime.WindowsRuntime, Version=4.0.0.0, Culture=neutral, PublicKeyToken=b77a5c561934e089')`
- Connect by address: `[Windows.Devices.Bluetooth.BluetoothLEDevice]::FromBluetoothAddressAsync([uint64]0x6393E18AA034)` (await via a reflection helper — see script).
- Enumerate: `GetGattServicesAsync()` → per service `GetCharacteristicsAsync()` → read `CharacteristicProperties`.

**Run it:**
```powershell
& "C:\Users\admin\Documents\APP\Jarvis Glasses\ble_probe.ps1"
```
**Gotcha:** reading characteristic *values* fails in PS 5.1 — the returned `IBuffer` is a `__ComObject` that won't marshal to `Windows.Storage.Streams.IBuffer` for `DataReader.FromBuffer`/`CryptographicBuffer.CopyToByteArray`. Workaround for values: use **Python `bleak`** or **nRF Connect**. (Service/characteristic *structure* enumerates fine.) `GattCharacteristic.AttributeHandle` is available in WinRT if you need handle↔UUID mapping from the PC.

**Quick presence check (no script):**
```powershell
Get-PnpDevice -Class Bluetooth | ? FriendlyName -match 'AIMB'
Get-PnpDevice -Class AudioEndpoint | ? Status -eq 'OK'   # confirms HFP/A2DP audio path
```

---

## B. BLE traffic sniffing (capture what the stock app sends) — the big one

**Goal:** capture the exact bytes the app writes to the glasses (e.g. the photo trigger). **Used in:** Session 02.
**Precondition:** glasses paired to the **phone**; phone on USB with debugging authorized (`& $adb devices` shows `device`).

### B.1 Enable the HCI snoop log (must use the UI — adb setprop is SELinux-blocked)
`adb shell setprop persist.bluetooth.btsnooplogmode full` → **fails** (permission denied). Instead drive the UI:
```powershell
& $adb shell am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS   # open Developer options
# screenshot + locate the "Enable Bluetooth HCI snoop log" row, tap it, pick "Enabled" (full)
& $adb shell screencap -p /sdcard/s.png ; & $adb pull /sdcard/s.png <local>  # then Read the PNG to see it
& $adb shell input tap <x> <y>
```
Then **restart Bluetooth so logging starts** (the dialog literally says to):
```powershell
& $adb shell svc bluetooth disable ; Start-Sleep 4 ; & $adb shell svc bluetooth enable
```
Glasses auto-reconnect. Re-open the app; wait until it shows "Connected".
**When done, set the snoop mode back to "Disabled" the same UI way (privacy).**

### B.2 Generate clean, timestamped events
Drive the app by adb taps and stamp the phone clock at each action, so events are findable in the log:
```powershell
"TAP " + (& $adb shell date +%H:%M:%S.%3N) ; & $adb shell input tap <x> <y>
```
Use **spaced, repeated** taps (e.g. 3 photos ~12–35 s apart) — the trigger then appears exactly N times with matching gaps. (See §C for finding tap coordinates.)

### B.3 Pull the log
The snoop log lives inside a bug report:
```powershell
& $adb bugreport "C:\...\bugreport.zip"
# extract FS/data/misc/bluetooth/logs/btsnoop_hci.log from the zip (System.IO.Compression)
```
**Privacy:** the bug report is a full system dump — delete it after extracting the one log file.

### B.4 Parse it (no Wireshark needed)
`parse_btsnoop.ps1` decodes ATT writes/notifies and ranks writes by frequency (the trigger = the one repeating N times):
```powershell
& "C:\Users\admin\Documents\APP\Jarvis Glasses\parse_btsnoop.ps1"
& "C:\Users\admin\Documents\APP\Jarvis Glasses\map_handle.ps1"   # handles → UUIDs from discovery
```
**btsnoop format facts baked into the parser:**
- File header: `"btsnoop\0"`(8) + version(4 BE) + datalink(4 BE). Android = `1002` (HCI-H4).
- Per record: origlen(4) inclLen(4) flags(4) drops(4) timestamp-µs(8), all **big-endian**, then packet.
- H4 packet: `d[0]`=type (`0x02`=ACL). ACL→L2CAP→ATT offsets: **CID** `d[7..8]` (ATT=`0x0004`), **opcode** `d[9]`, **attr handle** `d[10..11]` (LE), **value** `d[12..]`.
- ATT opcodes: `0x52`=Write Command, `0x12`=Write Request, `0x1B`=Notification, `0x09`=Read-By-Type Response (char discovery: `declH, props, valH, UUID`).
- **PowerShell gotcha:** `byte -shl 8` truncates to a byte → cast `[int]$b[i] -shl 8`. (This silently broke the first parse.)
**Confirmation method:** correlate write timestamps vs your tap timestamps. Identical gaps = proof (we matched 35.0 s/12.4 s to the millisecond).
**Identify the device:** different ACL connection handles (`d[1..2] & 0x0FFF`) = different BLE devices — this is how we found the camera is a *second* endpoint (ACL `0x042`, handle `0x008E`) vs the controller (ACL `0x041`).

---

## C. Driving the stock app via adb (UI automation)

**Goal:** operate the app (take photos, tap Import) without hands. **Used in:** Sessions 02–03.
```powershell
& $adb shell monkey -p com.aitowe.aitoglasses -c android.intent.category.LAUNCHER 1   # launch
& $adb shell screencap -p /sdcard/s.png ; & $adb pull /sdcard/s.png <local>            # then Read the PNG
```
**Get exact tap coordinates (don't guess):**
```powershell
& $adb shell uiautomator dump /sdcard/ui.xml ; & $adb pull /sdcard/ui.xml <local>
# parse <node text=".." bounds="[x1,y1][x2,y2]"> ; tap the CENTER
```
**Gotchas:** screen is 1080×2400; a Read-rendered screenshot is ~÷4.05 scale. Bottom-nav **icons** are tappable, the **text labels** below them often are not — tap the icon's bounds. Known coords this session: Take-photo `(199,970)`, Album icon `(837,2185)`, Home icon `(242,2189)`, Import button `(925,400)`.

---

## D. Network / transfer monitoring (what carries the image)

**Goal:** see how files move during Import. **Used in:** Session 03.
**Baseline + live sampling over adb (no root):**
```powershell
& $adb shell cmd wifi status | Select-String "connected to"     # current SSID
& $adb shell ip -o addr | Select-String "inet "                  # interfaces/IPs
& $adb shell ip neigh | Select-String "192.168.49"               # P2P peers
```
**What we found:** during Import a `p2p-wlan1-0` interface appears at **`192.168.49.1`** (phone = Wi-Fi-Direct Group Owner) and the glasses show as neighbor **`192.168.49.115`**. That `192.168.49.x` subnet = Android Wi-Fi Direct.
**Sampler pattern** (run in background, then trigger Import):
```powershell
$mon='i=0; while [ $i -lt 150 ]; do echo "T$(date +%H:%M:%S.%N)"; ip neigh; grep 31A8C0 /proc/net/tcp; sleep 0.2; i=$((i+1)); done > /sdcard/netmon.txt 2>&1'
& $adb shell $mon   # (run_in_background) ; then tap Import
```
**Limitation discovered:** `/proc/net/tcp|udp` sampling (even at 10 Hz) did **not** catch the data socket — the HTTP transfer is short/bursty and OkHttp-pooled. `/proc/net/nf_conntrack` (which would retain the flow) is **root-only** (permission denied). **Conclusion: don't chase the port with packet sampling — get it from the APK (§E).** Hex note: `192.168.49.x` little-endian in `/proc/net/*` = `…31A8C0`.

---

## E. APK pull + decompile (definitive protocol source) — how we got the real spec

**Goal:** read the app's actual transfer code. **Used in:** Session 04. This is the method that *worked* for the protocol.

### E.1 Pull the APK (it's a split APK; `base.apk` has the code)
```powershell
& $adb shell pm path com.aitowe.aitoglasses        # lists base.apk + split_config.*
& $adb pull "<path>/base.apk" "C:\...\apk\base.apk"
```
`base.apk` (~71 MB) = the DEX (Kotlin/Java) — fully decompilable. Native `.so` live in `split_config.arm64_v8a.apk` (Azure Speech, EIS, codecs — not needed for the transfer logic).

### E.2 Decompile with jadx (Java 17 present)
jadx was downloaded to `tools/jadx`. Run:
```powershell
& "C:\...\tools\jadx\bin\jadx.bat" --no-res -j 4 -d "C:\...\decompiled" "C:\...\apk\base.apk"
```
**Gotcha:** jadx exits `1` with "finished with errors, count: N" — that's a few un-decompilable methods; the other ~12,700 files are fine. Output lands in `decompiled/sources/`.

### E.3 Find the protocol (grep the decompiled tree)
The app is **not heavily obfuscated** under `com.aitowe.aitoglasses.*` (good class names); strings (URLs, IPs, ports, UUIDs) always survive. Productive searches (use the Grep tool over `decompiled/sources`):
```
WifiP2pManager|createGroup|groupOwnerAddress      → wifi/p2p/WifiP2pManagerSingleton.java
downloadMediaConfig|http://|/files/|configFileName → home/PictureFragment.java   ← the answer
ServerSocket|PORT                                  → ota/OTAActivity.java
glassesControl|LargeDataHandler                    → com/oudmon/ble/...           ← BLE control SDK
```
**What this yielded (the spec in `00_HANDOFF_START_HERE.md` §4):** glasses run an **HTTP server on port 80**; app GETs `http://<ip>/files/media.config` then `http://<ip>/files/<file>`. BLE control = vendor SDK `com.oudmon.ble` `LargeDataHandler.glassesControl(byte[])`; e.g. `{2,1,15}` resets/starts P2P.

---

## F. General adb / environment notes
- Confirm device: `& $adb devices -l` (status must be `device`, not `unauthorized`).
- Foreground app: `& $adb shell dumpsys activity activities | Select-String topResumedActivity`
- App uid: `& $adb shell ps -A -o USER,PID,NAME | Select-String aitowe` (`u0_aNNN` → uid `10000+NNN`).
- Bonded/connected BT: `& $adb shell dumpsys bluetooth_manager | Select-String "AIMB|Connected"`
- **PowerShell pitfall:** invoking a `.ps1` with `&` and piping `2>&1` into `Select-String` throws "Cannot run a document in the middle of a pipeline" — capture to a var first (`$o = & script.ps1; $o | …`).
- **Foreground `Start-Sleep` is blocked by the harness** for long waits — use `run_in_background` for monitors and let the completion notification arrive.

---

## G. Reproduce the headline result end-to-end (camera capture) — checklist
1. Glasses → phone (BT); phone → PC (USB, debugging on). Verify `& $adb devices`.
2. Enable HCI snoop (UI), restart BT (§B.1). Re-open app, wait "Connected".
3. `uiautomator dump` → find Take-photo + Import coords (§C).
4. 3× timed Take-photo taps (§B.2); then Album → Import.
5. `& $adb bugreport` → extract `btsnoop_hci.log` (§B.3).
6. `parse_btsnoop.ps1` → confirm `BC 41 03 00 10 50 02 01 01` @ handle `0x008E` appears 3× (§B.4).
7. Disable snoop log; delete the bug report.
8. For protocol detail: pull + jadx `base.apk`, grep for `/files/` and `glassesControl` (§E).
