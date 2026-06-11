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
- **Front / back buttons → BLE control** (sent over the glasses' oudmon/Jieli BLE channel to the stock app). Confirmed empirically: with our `MediaSession` active, front/back presses produced **no media-key events** — they are not AVRCP. With the stock app gone, **these are free to repurpose**, and our app can receive them by subscribing to the glasses' BLE *notify* characteristic.

## 3. Our plan: KEEP vs REPURPOSE

**KEEP NATIVE (do not intercept):**
- **Volume** (slide) — stays system volume.
- **Music** play/pause • prev • next (trackpad) — keep for music playback. *(Only repurpose these later if the director confirms glasses-music isn't used.)*

**REPURPOSE (front/back buttons — native intent already matches our features):**
| Glasses gesture | Native meaning | → Our app feature |
|---|---|---|
| **Press & hold BACK** | start recording | **Hold-to-talk** — start a voice question (ask JARVIS) |
| **Double-click BACK** | AI image recognition | **Look & Ask** — capture a frame + "what am I looking at?" |
| **Single-click FRONT** | take photo | **Capture to memory** — photo (firmware shoots it; we import + remember) |
| **Double-click FRONT** | start video | TBD (e.g. start Meeting Capture) |
| **Single-click BACK** | end recording | Stop / cancel current turn |

The mapping is almost 1:1 with the glasses' own labels ("AI recognition", "record", "photo"), so muscle memory barely changes.

## 4. How we capture them (implementation)
- Front/back button presses arrive as **BLE notifications** on the glasses' control channel. We subscribe to the notify characteristics (candidates from the GATT map: oudmon `0000ae02`/`0000ae04`, `de5bf729`, NUS `6e400003`, `0000fee3`) and log the bytes per gesture.
- **NEXT STEP:** extend `GlassesBleManager` to enable notifications on every notify characteristic, have the director perform each front/back gesture, capture the byte pattern → build a gesture→bytes map → route each to its feature (§3).
- Trackpad/music keys, *if* ever repurposed, go through the already-built `GlassesButtonController` (`MediaSession`). For now that session can stay inactive so we don't disturb music/volume.

## 5. Status
- ✅ Native mapping documented (this file).
- 🟡 Capturing the front/back button BLE notifications is the immediate next task (replaces the MediaSession approach for the camera/AI buttons).
