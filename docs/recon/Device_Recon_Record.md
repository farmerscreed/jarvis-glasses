# AIMB-G2 — Device Recon Record

*Concise, factual log of hands-on tests run against a real unit. Append new sessions at the top. Detailed roadmap implications live in §8b of the Project Brief — this file is the raw record.*

---

## Session 04 — 2026-06-10 · Full transfer protocol RE'd (APK static analysis)

**Method:** Pulled `base.apk` (71 MB) + `split_config.arm64_v8a.apk` via adb; decompiled with **jadx** (Java 17). App is Kotlin/Java (not native) → fully readable. Package `com.aitowe.aitoglasses`.

### ⭐ COMPLETE on-demand image pipeline (now fully specified)
1. **BLE control** — app uses vendor SDK **`com.oudmon.ble`** (`LargeDataHandler.glassesControl(byte[])`) as the control plane. E.g. P2P reset/start = `glassesControl({0x02,0x01,0x0F})` (`WifiP2pManagerSingleton.resetDeviceP2p`). This is the same BLE pipe the photo trigger (Session 02) rides.
2. **Wi-Fi Direct** — phone creates/join a P2P group (`WifiP2pManagerSingleton`, standard `android.net.wifi.p2p`). Glasses report their **own Wi-Fi IP back to the app over BLE** (`PictureFragment` line ~1573: BLE callback → `setGlassDeviceWifiIP(...)` → `downloadMediaConfig()`).
3. **HTTP pull** — **the glasses run an HTTP server on port 80**; the app GETs files from it:

| What | URL | Notes |
|---|---|---|
| Media manifest | `http://<glassesIP>/files/media.config` | lists photos/videos to import |
| Each media file | `http://<glassesIP>/files/<filename>` | saved to phone DCIM |
| Logs | `http://<glassesIP>/files/log/log.list` | debug logs |

Constants: `configFileName = "media.config"`, `logFileName = "log.list"` (`PictureFragment` L183–184). OTA uses the reverse direction — phone runs a `ServerSocket` on a fixed PORT and the glasses pull firmware (`OTAActivity`).

**→ To replicate in our own app:** send the BLE start command over the oudmon channel → bring up Wi-Fi Direct → read glasses IP (from BLE) → `GET http://<ip>/files/media.config` → `GET http://<ip>/files/<each>`. No vendor cloud involved in the image path; it's plain HTTP on the P2P link.

### Bonus architecture intel (from libs/assets)
- **Speech stack = Microsoft Azure** (`libMicrosoft.CognitiveServices.Speech.*`: STT, TTS, **KWS** keyword spotting, translation). The OEM's answer to our §7 STT/TTS question.
- **On-device NLU** = TFLite intent models (`assets/*_intent_model_with_noise.tflite`) for de/en/es/fr + `liblanguage_id`.
- **Camera SoC = Allwinner V821** confirmed (`assets/gyro/AW_EIS110_V821_Tdk8M.cfg`, `libeis_img`/`libgyro_eis110`/`libaweis_jni` = 8 MP + electronic image stabilization).
- Audio codecs: **Jieli Opus/Speex** (`libjl_opus`, `libjl_speex`). Storage: Tencent **MMKV**. Net: **OkHttp/Retrofit**.

### Conclusion
The camera path is now **fully reverse-engineered end to end** — trigger (BLE) + transport (Wi-Fi Direct) + protocol (HTTP `/files/`). This is a buildable spec, not just a feasibility result. The `com.oudmon.ble` SDK is the key dependency to obtain or reimplement.

**Artifacts:** `apk/base.apk`, `decompiled/` (jadx output, 12.7k files). Key files: `home/PictureFragment.java`, `wifi/p2p/WifiP2pManagerSingleton.java`, `ota/OTAActivity.java`, `com/oudmon/ble/base/communication/LargeDataHandler.java`.

---

## Session 03 — 2026-06-10 · WiFi image transfer mechanism PROVEN (Wi-Fi Direct)

**Setup:** Glasses on Pixel 8, phone on USB→PC. Drove the stock app via adb (UI taps): took photos with our known capture button, opened Album, tapped **Import**, while sampling phone network state (`ip addr`, `ip neigh`, `/proc/net/tcp`+`udp`, logcat).

### ⭐ CONFIRMED — Transfer is Wi-Fi Direct (P2P), on demand
When **Import** is tapped, the phone brings up a **Wi-Fi Direct group** and the image files transfer over it; the group tears down afterward. House WiFi / internet stays connected throughout (concurrent).

| Role | Address | Evidence |
|---|---|---|
| Phone = **P2P Group Owner** | `192.168.49.1` (iface `p2p-wlan1-0`) | appeared in `ip addr` during import; runs DHCP+DNS (`:53`) for the group |
| Glasses (camera subsystem) = **P2P client** | `192.168.49.115`, MAC `60:c2:2a:3b:88:64` | `ip neigh` on `p2p-wlan1-0` |

- App flow: tap Import → "Importing…" → P2P group forms (~10–15 s negotiation) → files pulled → group closes → photos appear in app album. Verified across **4 imports** (all succeeded).
- This is the **same camera subsystem** seen in Session 02 (its own BLE endpoint + now its own P2P identity) — consistent with a separate camera SoC.

### NOT pinned — exact transport port/protocol
The file bytes' exact port/protocol was **not** captured. `/proc/net/tcp`+`udp` sampled at up to 10 Hz across 3 imports showed **no data socket** on the `192.168.49.x` subnet (only the GO's DNS `:53`) — the transfer is too bursty to catch by polling and/or the socket binds to `0.0.0.0`. `/proc/net/nf_conntrack` (which would retain the flow) is **root-only** on this Pixel (permission denied). App package `com.aitowe.aitoglasses` (uid `u0_a456`) does not log the endpoint.

**To pin the protocol (next session), pick one:**
- **Static analysis of the APK** (cleanest, no root) — pull `com.aitowe.aitoglasses`, decompile, find the transfer host/port/URL. Doable entirely from the PC.
- Root + `tcpdump -i p2p-wlan1-0` (definitive packet capture) — needs root.
- PCAPdroid (VPNService capture) — may miss local P2P-subnet traffic; uncertain.

### Conclusion — biggest risk resolved
**§5 "Visual stream — UNPROVEN" → PROVEN reachable on demand.** Both halves of camera access now demonstrated on real hardware: **trigger** (BLE, Session 02) + **transfer** (Wi-Fi Direct, this session). The remaining work is protocol-level (replicating the transfer in our own app), not a feasibility question.

**Evidence:** `evidence_album_import.png`, `evidence_importing.png`, `evidence_wifidirect_netmon.txt` (shows `p2p-wlan1-0 192.168.49.1` appearing mid-capture).

---

## Session 02 — 2026-06-10 · Camera trigger CAPTURED (BLE sniff via adb)

**Setup:** Glasses paired to Pixel 8 (Android 16); phone on USB to PC. Bluetooth HCI snoop log enabled (Developer Options, driven over adb), 3 timed "Take photo" taps in the stock app, log pulled via `adb bugreport`, parsed with `parse_btsnoop.ps1` + `map_handle.ps1`.

**Intel gained**
- Stock app package: **`com.aitowe.aitoglasses`** · Firmware: **9.20.03** · App-reported wifi id: **2509141604** · Battery 100%.

### ⭐ CONFIRMED — Photo capture trigger
> Write **`BC 41 03 00 10 50 02 01 01`** to **ATT handle `0x008E`** on the camera LE endpoint.

Confirmed by exact timing: 3 taps spaced **35.0s / 12.4s** → 3 identical writes spaced **34.97s / 12.37s**. Behavioural + temporal match = unambiguous. App toast on each tap: *"Glasses start taking photos, please import from APP album"* (→ image stays on glasses, pulled later over WiFi).

### Two LE endpoints (dual-SoC, as hardware notes predicted)
| ACL conn | Endpoint | Evidence | Role |
|---|---|---|---|
| `0x041` | Main controller (JL7018F) | GATT discovery: NUS `6E400002`@`0x0010`, `DE5BF72A`@`0x0016`. Got **time-sync** write `01 26 06 10 19 53 25` (=YY MM DD HH MM SS) | Control / audio / time |
| `0x042` | **Camera subsystem** (sep. addr `…01:3c`) | `BC`-framed protocol; **photo trigger** `0x008E` | Camera capture |

### `BC` protocol frame (inferred)
`BC | cmd(1) | len(2 LE) | payload(len) | trailer(2, checksum?)`. Photo = cmd `0x41`, payload `10 50 02`. Other cmds seen on `0x008E`: `BC 40` (carries timestamp), `BC 42/43` (`01 B0`), `BC 47` (`00 20`), `BC 51` (`7E`) — a setup handshake before capture.

### Conclusion
**Camera "capture on demand" (§6) — TRIGGER HALF IS GREEN.** We have the exact, replayable command. The single biggest project risk is materially reduced.

### Open / next
- [ ] Confirm camera-endpoint **service/characteristic UUID** for `0x008E` (discovery was cached this session). Next: nRF Connect on the camera-endpoint addr, or clear the app's GATT cache and re-sniff, or WinRT probe of the `…01:3c` address from the PC.
- [ ] Verify by **replay** (nRF Connect → write the bytes → glasses should shoot). Timing proof is already conclusive; this is belt-and-suspenders.
- [ ] **WiFi transfer half** — trigger a photo *import* in the app; capture how the image bytes come across (the `wifi:2509141604` id is the lead).
- [ ] Decode the `BC 40…` setup handshake (is it required before each capture, or one-time per session?).

**Evidence:** `evidence_app_connected.png` (FW/wifi/connected), `evidence_photo_toast.png` (capture toast), `btsnoop_hci.log` (raw capture), `parse_btsnoop.ps1` / `map_handle.ps1` (analysis). *Note: `btsnoop_hci.log` contains all BT traffic from the capture window — delete if sharing externally.*

---

## Session 01 — 2026-06-10 · Windows PC over Bluetooth

**Setup:** Glasses (`AIMB-G2_A034`) Bluetooth-paired to Windows 11 PC. Intel Wireless BT adapter. No stock app involved (app lives on the phone). Tools: PowerShell + WinRT BLE, `netsh`, PnP enumeration. Probe script: `ble_probe.ps1`.

**Device identity**
- BT name: `AIMB-G2_A034` · BLE address: `63:93:E1:8A:A0:34`
- Chipset (per research): Jieli JL7018F · Camera (research): ~8MP/1080p
- Also paired (unrelated): `LWD-BT002` (separate audio device)

### Results

| # | Test | Result | Verdict |
|---|---|---|---|
| 1 | Audio profiles (HFP/A2DP) | `Headset … Hands-Free` (HFP) + `Headphones` (A2DP), both `OK` | ✅ **GREEN — proven** mic in + voice out |
| 2 | BLE GATT service/characteristic map | Full map captured (below) | ✅ **Mapped** |
| 3 | BLE static value reads (FW/serial) | Blocked — PS↔WinRT `__ComObject`→`IBuffer` cast fails | ⚠️ Use `bleak`/nRF Connect |
| 4 | WiFi AP scan (glasses idle) | No glasses AP; not on LAN; only house WiFi visible | 🔵 WiFi/camera is **on-demand only** |
| 5 | Camera spike (image transfer) | Not runnable from PC — needs phone + stock app to trigger | ⛔ **Deferred to phone session** |

### BLE GATT map (the key artifact)

| Service | Characteristics (props) | Likely role |
|---|---|---|
| `6E40FFF0-…-24DCCA9E` | `6E400002` (Write/WriteNoResp), `6E400003` (Notify) | **Nordic UART clone — primary app command channel (prime suspect)** |
| `0000AE30` (Jieli) | `AE01` (WNR), `AE02` (Notify), `AE03` (WNR), `AE04` (Notify), `AE05` (Indicate), `AE10` (R/W) | Jieli control + data/OTA; `AE10` config register |
| `0000AE3A` | `AE3B` (WNR), `AE3C` (Notify) | Secondary Jieli command/notify pair |
| `DE5BF728-…-012A5DC7` | `DE5BF72A` (Write/WNR), `DE5BF729` (Notify) | Custom serial pipe (purpose TBD) |
| `00003802` | `00004A02` (R/W/Notify) | Vendor data/control characteristic |
| `0000FEE1` | `0000FEE3` (R/W/Notify) | Likely OTA / control |
| `0000180A` | Serial / HW rev / FW rev / System ID (Read) | Device Info — values not yet decoded |
| `1800` / `1801` | standard GAP / GATT | standard |

### Conclusions
- **Audio half of roadmap = de-risked on real hardware.** Build it now with confidence.
- **Camera spike reframed into two sniffable halves:** (1) BLE *trigger* command (likely `6E400002`/`AE01`) → answers the hard "capture on demand" question; (2) WiFi *transfer* of the image file.
- **WiFi only wakes on BLE command** → camera spike must be driven from the phone; PC can sniff the AP once it appears.

### Open / next
- [ ] Capture BLE bytes HeyCyan writes when taking a photo (nRF Connect or Android HCI snoop log) → find the trigger command.
- [ ] Decode `180A` Device Info values (`bleak` / nRF Connect) → firmware version.
- [ ] Phone session: trigger photo sync, observe glasses' WiFi AP, port-scan it, sniff transfer protocol.
- [ ] Decide BLE tooling: set up Python `bleak` on PC vs. nRF Connect on phone.

---

## Reference
- **Probe script:** `ble_probe.ps1` (WinRT, no deps — re-maps services/characteristics)
- **Roadmap & implications:** `AIMB-G2_AI_Companion_Project_Brief.md` §8b
- **Radio model:** Bluetooth = control + audio (single-point; PC *or* phone, not both). WiFi = image transfer (on-demand, woken via BLE).
