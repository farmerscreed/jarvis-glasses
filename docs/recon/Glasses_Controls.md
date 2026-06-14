# Glasses Controls — native gestures & our repurposing plan

*From the AIMB-G2 manual (provided by the director, 2026-06-11). This governs how we map physical glasses gestures to our app's features.*

## 1. Native gesture map (what each does out of the box)

### Trackpad — Music (sent as Bluetooth **AVRCP media keys**)
| Gesture | Native function |
|---|---|
| Double-click (right) | Play / Pause |
| Slide front ↔ back | Volume up / down |
| Triple-click | Previous song |
| Long-press | Next song |

### Front & Back buttons — Camera / AI (sent as **BLE control commands** to the app)
| Gesture | Native function |
|---|---|
| Single-click **front** | Take photo |
| Double-click **front** | Start video |
| Single-click **front** (while recording) | End video |
| Press & hold **back** | Start (audio) recording |
| Single-click **back** (while recording) | End recording |
| Double-click **back** | AI fast image recognition |

## 2. Two control channels (the key insight)
There are **two completely different transports**, and that determines what we can safely repurpose:

- **Trackpad / music → AVRCP** (standard Bluetooth media keys). These route to whatever app holds the active `MediaSession`. **Volume is handled by the Android system** — our app never sees it and shouldn't try to.
- **Front / back buttons → on-device firmware capture + a BLE notification.** **IMPORTANT (corrected 2026-06-11):** pressing a button makes the glasses **capture the photo/video/audio themselves, in firmware, stored on the glasses' internal storage — no app required.** Any app (stock or ours) is only needed *later* to **sync** the file over Wi-Fi. Separately, the glasses also emit a **BLE notification** so a connected app knows a button was pressed. Confirmed empirically that these are NOT AVRCP (our active `MediaSession` saw nothing). So we **cannot and should not try to "take over" these buttons** — the capture always happens. Our app's job is to **listen to the notification and enrich** (sync + apply AI), not to override.

> **V2 note:** video recording is a planned V2 feature — **do not erode it.** The video gestures must keep doing their native firmware recording; our app should only *sync/process* video, never block it.

## 3. Our plan: KEEP native captures, LISTEN + ENRICH

Decided (director, 2026-06-11): **keep all native functions; never override the firmware capture.** Our app reacts to each button's BLE notification by **syncing the new file and applying AI/memory on top** — it does not take the gesture away.

**KEEP NATIVE (untouched):**
- **Volume** (slide) — system volume.
- **Music** play/pause • prev • next (trackpad) — kept for music.
- **All captures** — photo, video, audio recording stay firmware-driven and stored on the glasses.

**LISTEN + ENRICH (our app reacts to the BLE notification of each press):**
| Glasses gesture | Native (preserved) | Our app reacts by |
|---|---|---|
| Single-click FRONT | take photo | auto-sync the photo → remember / describe it |
| Double-click BACK | "AI image recognition" (captures a frame) | **Look & Ask** — sync the frame → Claude vision "what is this?" |
| Hold BACK / single-click BACK | start / stop audio recording | sync the clip → transcribe → meeting note / voice memo |
| Double-click FRONT / single-click FRONT (recording) | start / stop **video** | **V2: sync & process video — do NOT block.** Leave fully native for now. |

Pure voice questions to JARVIS (no image) use the **wake word** (already built), not a capture button.

The point: the glasses already do the *capture*; we add the *cloud brain + memory* by syncing what they captured.

## 4. The decoded event protocol (recon DONE on device, 2026-06-12)

We subscribed to all 9 notify/indicate characteristics and pressed every gesture. **Everything
arrives on `de5bf729`** (the same char as the Wi-Fi IP notify), framed as
`BC 73 <len:2LE> <CRC16-MODBUS:2LE> <payload>` (note opcode `73` for events vs `41` for commands):

| payload | event | observed |
|---|---|---|
| `01 <photos:u16LE> <videos:u16LE> <audio:u16LE> 01` | **capture saved** + current file inventory | fires on photo taken, audio-rec stop, video-rec stop. Counted 1→2 photos, then +1 audio, then +1 video across the test presses. |
| `02 00 0C 02 00` | **AI gesture** (double-click BACK) | pure button signal — **no file is saved** (inventory unchanged). The app must trigger its own capture to get a frame. |
| `0B <counter>` | **activity heartbeat** | every ~3 s during a recording AND during a Wi-Fi transfer session. Counter drifted 0x26→0x32, non-monotonic — meaning unknown, safe to ignore. |
| `08 <ip:4>` | glasses' Wi-Fi IP | known from Phase 2. |
| `09 FF 02` / `09 FF 03` | unknown status | seen around Wi-Fi-session transitions (start/teardown). Ignored. |

**Hard-won integration gotchas (verified live, 2026-06-12):**
1. **`BC 41` frames on the same char are command-ACK echoes**, payload mirroring the sent command
   (capture cmd `02 01 01` → ack `02 01 01 FF 01`; first attempt acks `… FF FF`). An ACK parsed
   naively as an event reads `02…` = AiGesture → triggers a capture → whose ACK triggers another:
   a self-sustaining ~60 ms storm we hit in testing. **Events are strictly `BC 73` — check byte 1.**
2. **The glasses DELETE their stored files after a successful transfer** and then emit a
   zero-inventory event `01 00 00 00 00 00 00 01`. Treating that as "new capture" causes an
   infinite sync loop (also hit in testing). Only an inventory **increase** means a new capture.
3. The **Wi-Fi-start ACK carries the P2P group credentials in cleartext**:
   `BC 41 25 00 <crc> 02 01 04 01 14 00 09 00 "AIMB-G2_6393E18AA034" "123456789"`
   (SSID len-prefixed 0x14, passphrase len 0x09). Useful if we ever join the group manually
   instead of via Wi-Fi Direct negotiation.

Parser: `GlassesEvent.kt` in `:device:ble` (enforces the `BC 73` rule). Reactions (inventory-gated
auto-sync + route by file type) live in `HomeViewModel` (`onCaptureSaved`/`onAiGesture`/`routeNewFile`).

4. **The camera shares the BT radio with audio, but a clean teardown frees it — voice-vision works
   mid-conversation (CORRECTED 2026-06-14; the 2026-06-13 "can't fix" conclusion was wrong).**
   A capture sent while BT audio is *actively streaming* (SCO held + A2DP playing) is ACK'd (`BC 41`)
   but takes no photo. The earlier conclusion — "releasing SCO isn't enough because the system holds
   A2DP and the app can't force-drop it" — was **incorrect**. The app does **not** need to force-drop
   A2DP: fully releasing SCO (end the SCO session → `AudioManager.MODE_NORMAL` → clear the comm device)
   and waiting **~3.5 s for the idle A2DP stream to SUSPEND** is enough — the camera then captures and
   `CaptureSaved` fires. **Verified 2026-06-14:** "what am I looking at" captured + described reliably
   across repeated back-to-back attempts inside a live conversation.
   The real blockers behind the 06-13 failures were three *software* bugs, not a hardware limit:
   (a) the audio wasn't being fully torn down / not enough settle time before the capture;
   (b) a **stale auth token** made every `vision` call 401 (`vision=FAILED`), so even captured photos
   weren't described; (c) the on-demand path passively waited on the **autonomous collector**, which
   pulled the whole backlog, raced, and a hung Wi-Fi-Direct sync held the sync mutex forever — wedging
   every later capture. Fixes: `BtAudioEngine.releaseForCamera()` (clean teardown + settle) and a
   **deterministic** `GlassesCaptureReactor.captureAndDescribe()` that owns the flow under the mutex,
   suppresses the collector for its own event, waits for the new-photo event, pulls, and describes only
   the **newest** photo (the one just taken) — never a stale backlog photo. Whole ceremony is time-boxed
   so it can never wedge a later capture. See `docs/SESSION_LOG_2026-06-14.md`.

- Trackpad/music keys, *if* ever repurposed, go through the already-built `GlassesButtonController` (`MediaSession`). For now that session can stay inactive so we don't disturb music/volume.

## 5. Status
- ✅ Native mapping documented (this file).
- ✅ **Firmware-autonomous capture VERIFIED on device (2026-06-11):** with both our app AND the stock app force-stopped (zero PIDs), single-tapping the front button still captured photos. The stock app then showed "10 media resources can be imported" — captures the glasses made entirely on their own, with no app running. Confirms: buttons capture in firmware → stored on glasses → synced later.
- 🟡 **Next tasks:**
  1. **Sync pipeline** (the substance): pull captured media off the glasses into our app via the reverse-engineered path (BLE start cmd → Wi-Fi Direct → `GET /files/media.config` → `GET /files/<file>`), then apply AI/memory.
  2. **BLE notification capture**: subscribe to the notify characteristics, press each gesture, record the bytes → so the app can react *instantly* to a press (auto-sync that capture + run the matching feature).
