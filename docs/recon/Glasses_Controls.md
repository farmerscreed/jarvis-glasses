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

## 4. How we capture them (implementation)
- Front/back button presses arrive as **BLE notifications** on the glasses' control channel. We subscribe to the notify characteristics (candidates from the GATT map: oudmon `0000ae02`/`0000ae04`, `de5bf729`, NUS `6e400003`, `0000fee3`) and log the bytes per gesture.
- **NEXT STEP:** extend `GlassesBleManager` to enable notifications on every notify characteristic, have the director perform each front/back gesture, capture the byte pattern → build a gesture→bytes map → route each to its feature (§3).
- Trackpad/music keys, *if* ever repurposed, go through the already-built `GlassesButtonController` (`MediaSession`). For now that session can stay inactive so we don't disturb music/volume.

## 5. Status
- ✅ Native mapping documented (this file).
- ✅ **Firmware-autonomous capture VERIFIED on device (2026-06-11):** with both our app AND the stock app force-stopped (zero PIDs), single-tapping the front button still captured photos. The stock app then showed "10 media resources can be imported" — captures the glasses made entirely on their own, with no app running. Confirms: buttons capture in firmware → stored on glasses → synced later.
- 🟡 **Next tasks:**
  1. **Sync pipeline** (the substance): pull captured media off the glasses into our app via the reverse-engineered path (BLE start cmd → Wi-Fi Direct → `GET /files/media.config` → `GET /files/<file>`), then apply AI/memory.
  2. **BLE notification capture**: subscribe to the notify characteristics, press each gesture, record the bytes → so the app can react *instantly* to a press (auto-sync that capture + run the matching feature).
