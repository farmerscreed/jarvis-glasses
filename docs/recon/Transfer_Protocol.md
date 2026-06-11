# AIMB-G2 Media Transfer Protocol (complete, implementation-ready)

*Decoded 2026-06-11 from the decompiled stock app (`PictureFragment`, `AlbumDepository`, `WifiP2pManagerSingleton`, `com.oudmon.ble` `LargeDataHandler`) + CRC reversal. This is everything needed to pull captured media off the glasses ourselves.*

## BLE command framing (oudmon `glassesControl`)
Every `glassesControl(payload)` is written to **service `de5bf728-d711-4e47-af26-65e3012a5dc7`, characteristic `de5bf72a-…` (write, no response)** as:
```
BC | 41 | len(2, little-endian) | CRC16(2, little-endian) | payload
```
- `BC` = frame start, `41` (0x41=65) = ACTION_GLASSES_CONTROL.
- **CRC = CRC-16/MODBUS** (poly 0x8005, init 0xFFFF, refin/refout true, xorout 0) over **the payload only**, stored little-endian. *(Reversed from the known camera-trigger frame and verified.)*

### Known commands (`glassesControl` payloads → full frame)
| Purpose | payload | full BLE frame to write |
|---|---|---|
| **Take photo** (verified) | `02 01 01` | `BC 41 03 00 10 50 02 01 01` |
| **Start Wi-Fi transfer** | `02 01 04` | `BC 41 03 00 D0 53 02 01 04` |
| **Reset/stop P2P** | `02 01 0F` | `BC 41 03 00 91 94 02 01 0F` |
(payload `02 01 XX`: `02`=glass-model-control, `01`=enable, `XX`=work type: 01 photo, 04 wifi-transfer, 0F reset.)

## BLE notifications (status back from glasses)
Subscribe to **service `de5bf728`, characteristic `de5bf729-…` (notify)**. Notification body = same `BC 41 len crc payload` framing; `payload[0]` (i.e. raw byte index 6) is the type:
| payload[0] | meaning | data |
|---|---|---|
| `0x08` | **Wi-Fi IP address** | bytes [7..10] = IP octets → `a.b.c.d` |
| `0x01` | Wi-Fi enable status | work-type-in-progress |
| `0x05` | charging status | [8]=1 if charging |
| `0x09` | error | [7]=code (0xFF=device busy) |
| `0x0B` | temperature | [7]=°C |
| `0x04` (in a control *response*) | media counts | imageCount[8..9], videoCount[10..11], recordCount[12..13] |

## Full import sequence (what "Import" does)
1. Preconditions: BLE connected, battery >15% (or charging), Wi-Fi on.
2. **`WifiP2pManager.discoverPeers()`** (mirror `WifiP2pManagerSingleton`); 16 s discovery timeout, 15 s connect timeout.
3. **BLE write Wi-Fi-start:** `BC 41 03 00 D0 53 02 01 04` to `de5bf72a`.
4. **P2P connect** to the glasses peer → `onConnected(WifiP2pInfo)`. (Phone becomes group owner; the glasses' own IP arrives via BLE, not from `groupOwnerAddress`.)
5. **BLE notify** `0x08` arrives on `de5bf729` → parse IP (bytes 7–10). When *both* P2P-connected AND IP-received are true, proceed.
6. **HTTP GET `http://<ip>/files/media.config`** — a **line-based text file, one filename per line** (e.g. `photo-20250611120530.jpg`, `video-….mp4`, `recording-….opus`).
7. For each filename: **HTTP GET `http://<ip>/files/<filename>`**, save to album dir. Sequential, with progress.
8. On empty queue → done; reset P2P.

## File types
filename/extension implies type: `.jpg`=photo, `.mp4`=video (firmware also runs EIS stabilization), `.opus`/recording=audio. The glasses captured all of these in firmware (no app needed); this protocol just pulls them.

## Our implementation map
- `:device:ble` `GlassesBleManager`: add `startWifiTransfer()` (write the Wi-Fi-start frame) + subscribe `de5bf729`, parse `0x08` → emit glasses IP. (Use the CRC-16/MODBUS framer for all commands; we can drop the captured-bytes constants and generate frames.)
- `:device:wifi` `GlassesP2pManager` (mirror `WifiP2pManagerSingleton`) + `MediaTransferClient` (OkHttp: GET media.config → GET each file).
- Orchestrate: BLE start ∥ P2P connect → on (IP + connected) → HTTP pull → store + upload + AI (vision/transcribe) → memories.
