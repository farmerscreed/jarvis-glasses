# 00 — START HERE (Session Handoff)

> **If you are a fresh AI/dev session with no prior context, read this file first, then `Methodology_Reproducible_Tests.md`.**
> This is the single source of truth for *where the project is* and *exactly what to do next*. Last updated: **2026-06-10**.

---

## 0. What this project is

Building a personal AI companion app (codename **ECHO/JARVIS**) for the **AIMB-G2 smart glasses** (rebadged: Erilles, ROSE STAR, "G2 Pro"). The glasses are *sensors + a speaker*; all intelligence runs on an Android phone + cloud. The differentiator is a shared **Personal Memory Index** (vector DB) that every feature reads/writes.

The vision, architecture, feature set, and roadmap live in **`AIMB-G2_AI_Companion_Project_Brief.md`** — read it for the "why". This file covers the "where we are / what to do".

---

## 1. Document map (read in this order)

| File | What it is |
|---|---|
| **`00_HANDOFF_START_HERE.md`** | ← you are here. State + next steps. |
| `AIMB-G2_AI_Companion_Project_Brief.md` | The product brief / roadmap (vision, features, phases). |
| `01_IMPLEMENTATION_PLAN.md` | **The approved build plan** — stack, 7-layer architecture, Gradle modules, Supabase design, phased roadmap. Building **local Supabase on Docker Desktop first**, cloud later. |
| `Device_Recon_Record.md` | **Chronological lab notebook** — Sessions 01–04, every test + result. The evidence trail. |
| `Methodology_Reproducible_Tests.md` | **HOW to redo every test** — exact tools, commands, gotchas. Use to reproduce or extend. |
| `Camera_Trigger_Capture_Guide.md` | Standalone how-to for the BLE photo-trigger capture (superseded by Methodology §B, kept for detail). |
| `ble_probe.ps1` | Reusable script: enumerate glasses BLE GATT services/characteristics (WinRT). |
| `parse_btsnoop.ps1` | Reusable script: parse an Android `btsnoop_hci.log` → ATT writes/notifies. |
| `map_handle.ps1` | Reusable script: map ATT handles → UUIDs from a btsnoop capture. |
| `apk/base.apk` | The stock app (`com.aitowe.aitoglasses`) pulled from the phone. |
| `decompiled/` | jadx output of base.apk (12,744 `.java` files) — **source of truth for the protocol**. |
| `tools/jadx/` | jadx decompiler (reusable). |
| `evidence_*.png` / `evidence_*.txt` / `btsnoop_hci.log` | Raw evidence from the sessions. |

---

## 2. STATUS DASHBOARD (as of 2026-06-10)

| Capability | Status | Evidence |
|---|---|---|
| **Audio in/out (mic + speaker)** | ✅ **PROVEN** — standard Bluetooth HFP + A2DP | Session 01 |
| **BLE control surface mapped** | ✅ **DONE** — full GATT map of both endpoints | Sessions 01–02 |
| **Photo capture on demand** | ✅ **PROVEN** — exact BLE trigger command captured | Session 02 |
| **Image transfer off glasses** | ✅ **PROVEN** — Wi-Fi Direct, on demand | Session 03 |
| **Exact transfer protocol** | ✅ **FULLY SPEC'd** — HTTP `/files/` on port 80 | Session 04 (APK) |
| **AI provider hints** | ℹ️ OEM uses **Azure Speech** + on-device TFLite NLU | Session 04 |
| **Cloud memory index (Supabase)** | ⬜ **NOT STARTED** | — |
| **Any app code written** | ⬜ **NOT STARTED** — all work so far is recon/RE | — |

**Bottom line:** The project's single biggest risk (the brief's §5 "Visual stream — UNPROVEN") is **fully retired**. The camera path is not just feasible, it's a buildable spec. No remaining hardware unknowns block the build.

---

## 3. HARDWARE FACTS (verified on real device)

- **Glasses BT name:** `AIMB-G2_A034`
- **Main controller (Jieli JL7018F) — BLE/classic address:** `63:93:E1:8A:A0:34`
- **Camera subsystem (Allwinner V821, 8 MP + EIS) — separate BLE/P2P identity:** address ending `…01:3c`, Wi-Fi-Direct client IP `192.168.49.115`, MAC `60:c2:2a:3b:88:64`
- **Firmware:** `9.20.03`
- **The glasses are TWO chips with TWO BLE endpoints** (controller + camera). This matters: the photo trigger goes to the *camera* endpoint, not the controller.
- **Lenses:** photochromic (NOT a display — there is no HUD; the product is voice-first).
- **Stock app:** `com.aitowe.aitoglasses` ("HeyCyan"/"CyanGlasses"), MainActivity `com.aitowe.aitoglasses/.MainActivity`, runtime uid `u0_a456`.

---

## 4. THE REVERSE-ENGINEERED CAMERA PIPELINE (the crown jewels)

Everything needed to pull an image from the glasses ourselves:

### Step 1 — Trigger a capture (BLE)
Write to the **camera BLE endpoint**, ATT handle `0x008E`:
```
BC 41 03 00 10 50 02 01 01
```
- Confirmed by timing-correlation (3 app taps → 3 identical writes, gaps matched to the ms — Session 02).
- Frame format (inferred): `BC | cmd | len(2,LE) | payload | 2-byte trailer(checksum?)`. Photo = cmd `0x41`, payload `10 50 02`.
- The app issues all BLE via the vendor SDK **`com.oudmon.ble`** → `LargeDataHandler.glassesControl(byte[])`.

### Step 2 — Bring up the transport (Wi-Fi Direct)
- App uses standard `android.net.wifi.p2p` (`WifiP2pManagerSingleton`).
- A P2P group forms on demand and tears down after. In our captures the **phone was Group Owner** (`192.168.49.1`), glasses were client (`192.168.49.115`). House WiFi/internet stays connected concurrently.
- BLE start/reset command observed: `glassesControl({0x02, 0x01, 0x0F})`.
- **The glasses report their own WiFi IP back to the app over BLE**, which triggers the download.

### Step 3 — Pull the files (HTTP — the glasses are an HTTP server on **port 80**)
```
GET http://<glassesIP>/files/media.config     → manifest of media to import
GET http://<glassesIP>/files/<filename>        → each photo/video  (saved to phone DCIM)
GET http://<glassesIP>/files/log/log.list       → device logs
```
- Hardcoded in `decompiled/.../home/PictureFragment.java` (constants `configFileName="media.config"`, `logFileName="log.list"`).
- **No vendor cloud touches the images** — plain HTTP over the local P2P link.
- OTA is the reverse: phone runs a `ServerSocket` on a fixed PORT, glasses pull firmware (`ota/OTAActivity.java`).

### Our build recipe
`oudmon BLE start cmd` → `Wi-Fi Direct group` → `read glasses IP from BLE` → `GET /files/media.config` → `GET /files/<each>`.
**Key dependency:** the `com.oudmon.ble` SDK — obtain it, or reimplement its `glassesControl` framing (byte formats are in the snoop logs + decompiled calls).

### Full BLE GATT map (main controller endpoint, address A0:34)
| Service | Characteristics (properties) | Role |
|---|---|---|
| `6E40FFF0-…-24DCCA9E` | `6E400002`(Write) @handle `0x0010`, `6E400003`(Notify) | **Nordic-UART command channel** (got the time-sync write) |
| `0000AE30` (Jieli) | `AE01`(WriteNoResp) `AE02`(Notify) `AE03`(WNR) `AE04`(Notify) `AE05`(Indicate) `AE10`(R/W) | Jieli control + data/OTA |
| `0000AE3A` | `AE3B`(WNR) `AE3C`(Notify) | Secondary Jieli pair |
| `DE5BF728-…-012A5DC7` | `DE5BF72A`(Write) @handle `0x0016`, `DE5BF729`(Notify) | Custom data pipe |
| `00003802` | `00004A02`(R/W/Notify) | Vendor data |
| `0000FEE1` | `0000FEE3`(R/W/Notify) | Likely OTA |
| `0000180A` | Serial / FW / HW / SystemID (Read) | Device info |

> Note: the **photo trigger handle `0x008E` is on the *camera* endpoint** (a different BLE device/ACL connection), whose GATT discovery was cached during capture — its UUID is not yet pinned (see Open Items).

### Stock-app tech stack (from APK, useful reference)
- **Speech = Microsoft Azure** (STT/TTS/keyword-spotting/translation) · on-device **TFLite** intent models (en/de/es/fr) + language-ID.
- Audio codecs: **Jieli Opus/Speex**. Storage: **MMKV**. Networking: **OkHttp/Retrofit**.

---

## 5. ENVIRONMENT & TOOLING (already installed on this PC)

- **OS:** Windows 11, PowerShell 5.1. Primary working dir for the project: `C:\Users\admin\Documents\APP\Jarvis Glasses`.
- **adb:** `C:\Users\admin\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- **Java:** Adoptium JDK 17 (`C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot`)
- **jadx:** `C:\Users\admin\Documents\APP\Jarvis Glasses\tools\jadx\bin\jadx.bat`
- **Android build-tools:** `…\Android\Sdk\build-tools\{35,36,37}.x` (has `aapt2`, `dexdump`, `d8`)
- **Test phone:** Google **Pixel 8** (codename `shiba`), **Android 16**, USB-debugging authorized.
- **WinRT BLE** works from PowerShell (no install) — used for `ble_probe.ps1`.
- NOT installed: Wireshark/tshark (not needed — `parse_btsnoop.ps1` replaces it), jadx/apktool were downloaded into `tools/`.

---

## 6. CRITICAL CONSTRAINTS & GOTCHAS (read before touching hardware)

1. **Bluetooth is single-point.** The glasses pair to the **PC _or_ the phone, not both**. WinRT BLE enumeration needs them on the PC; the app + sniffing need them on the phone. You *time-share* the link.
2. **The camera is a separate subsystem** with its own BLE endpoint and its own Wi-Fi-Direct identity. Don't assume one address.
3. **HCI snoop log:** can't be enabled via `adb setprop` (SELinux blocks it). Must toggle it in **Developer Options UI**, then **restart Bluetooth** (`adb shell svc bluetooth disable; …enable`) for it to take effect. **Turn it OFF when done** (privacy — it logs all BT traffic).
4. **`/proc/net/nf_conntrack` is root-only** on this Pixel; live socket sampling misses the bursty HTTP transfer. Use the **APK (static analysis)** for protocol detail, not packet sniffing.
5. **PowerShell quirk:** `byte -shl n` truncates to a byte — always cast `[int]` first (bit us in `parse_btsnoop.ps1`).
6. **btsnoop format:** datalink `1002` = HCI-H4 with a 1-byte type prefix → ATT offsets are: L2CAP CID `d[7..8]`, ATT opcode `d[9]`, attr handle `d[10..11]`, value `d[12..]`.
7. **Privacy/legal (from brief §8):** build only *consensual* features; steer away from face-recognition / recording non-consenting people. Needs director sign-off before any such feature.

---

## 7. OPEN ITEMS / NEXT STEPS

**Recon loose ends (small, optional):**
- [ ] Pin the **UUID of camera-endpoint handle `0x008E`** (its GATT discovery was cached). Do it via nRF Connect on the camera endpoint, or WinRT `GattCharacteristic.AttributeHandle` from the PC (re-pair glasses to PC first).
- [ ] Decode the `oudmon` `glassesControl` framing fully (read `decompiled/.../com/oudmon/ble/base/communication/LargeDataHandler.java`) to enumerate all command bytes (capture photo, video, etc.).
- [ ] Optional live-confirm: replay `BC 41 03 00 10 50 02 01 01` via nRF Connect → glasses should shoot.

**Build (the project proper — nothing here is blocked):**
1. **Phase 0 — Cloud index:** stand up **Supabase** (Postgres + `pgvector`), schema for memories (embedding, media ref, transcript, timestamp, location, tags). (Supabase MCP/skill is available in this environment.)
2. **Phase 0 — Audio pipeline:** Android app connects to glasses as a BT headset; prove mic capture + speaker playback.
3. **Phase 1 — First loop (audio-only):** Meeting Capture → transcribe → summarize → store in index → recall by voice. Proves the whole pipeline with zero camera dependency.
4. **Phase 2 — Vision:** implement the camera client using §4's recipe (BLE→Wi-Fi-Direct→HTTP), then Look-&-Ask / Read-it-to-me / visual second-brain.
5. Decide: obtain vs. reimplement the `com.oudmon.ble` SDK.

**Suggested immediate next action:** either (a) write the Android **camera-client module** skeleton from §4, or (b) stand up the **Supabase memory schema**. Both are unblocked.

---

## 8. How to verify the glasses are present (quick sanity check)
- **On PC (glasses paired to PC):** `Get-PnpDevice -Class Bluetooth | ? FriendlyName -match 'AIMB'`
- **On phone (glasses paired to phone, USB connected):** `adb shell dumpsys bluetooth_manager | findstr AIMB`
- Full reproducible procedures for every test are in **`Methodology_Reproducible_Tests.md`**.
