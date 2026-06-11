# Camera Trigger Capture — Step-by-Step

> **STATUS (2026-06-10): the trigger was successfully captured.** Result: write `BC 41 03 00 10 50 02 01 01` to ATT handle `0x008E`. This guide is the original plan, kept for detail; for the up-to-date reproducible method see `Methodology_Reproducible_Tests.md` §B, and for current state see `00_HANDOFF_START_HERE.md`.

*Goal: capture the exact BLE bytes the stock HeyCyan app writes to the glasses to make them take a photo. If we find them, we can replay the trigger from our own app → "capture on demand" without the Jieli SDK. This is the hard half of the camera spike (§6 / §8b).*

**Method:** Android Bluetooth HCI snoop log → Wireshark. No special hardware. (nRF Connect can NOT sniff the stock app — it's only used in Phase 4 to replay.)

**You need:** the phone (with HeyCyan), the glasses, this Windows PC, a USB cable, and ~30 min.

---

## Phase 0 — Prep

1. **Free the glasses' Bluetooth.** On this PC: Settings → Bluetooth → `AIMB-G2_A034` → **Disconnect** (or Remove). The glasses are single-point — they must be paired to the *phone* for this.
2. **Pair glasses → phone**, open HeyCyan, confirm you can take a photo normally.
3. **On the PC, install Android platform-tools (adb):**
   - Download "SDK Platform-Tools for Windows" from `https://developer.android.com/tools/releases/platform-tools`, unzip (e.g. to `C:\platform-tools`).
4. **On the phone, enable Developer Options:** Settings → About phone → tap **Build number** 7×.
5. **In Developer Options, enable:**
   - **USB debugging**
   - **Bluetooth HCI snoop log** → set to **Enabled** (some phones: "Filtered"/"Full" — pick **Full**).
6. **Make the snoop log take effect:** toggle **Bluetooth OFF then ON** (the log only starts capturing after a fresh BT restart). Reconnect the glasses.
7. **Verify adb sees the phone:** plug in USB, accept the "Allow USB debugging?" prompt on the phone, then on PC run:
   ```
   C:\platform-tools\adb.exe devices
   ```
   You should see your device listed as `device` (not `unauthorized`).

---

## Phase 1 — Clean capture session (timing is everything)

Do a slow, *timed, repeated* sequence so the trigger stands out from background chatter.

1. With glasses connected and HeyCyan open, **sit idle ~15 seconds** (let routine traffic settle).
2. **Take exactly ONE photo.** Note the wall-clock second.
3. **Wait ~15 seconds.** Do nothing else.
4. **Take a second photo.** Wait ~15s. **Take a third.**
5. Stop. (Three spaced presses = a repeating pattern we can lock onto, while avoiding photo *sync/download* traffic which is the WiFi half, not the trigger.)
6. Optionally jot the three timestamps — helps correlate in Wireshark.

> Keep it minimal: don't change settings, don't sync/transfer, don't touch other BT devices during capture.

---

## Phase 2 — Pull the log to the PC

The snoop log lives inside a bug report on modern Android. From the PC:

```
C:\platform-tools\adb.exe bugreport C:\Users\admin\Documents\APP\Jarvis Glasses\bugreport.zip
```

- Unzip it. The capture is at: `FS\data\misc\bluetooth\logs\btsnoop_hci.log` (older phones: `FS\data\log\bt\` or `/sdcard/btsnoop_hci.log`).
- Copy that `btsnoop_hci.log` into the project folder.

*Fallback if `adb bugreport` is blocked:* Developer Options → **"Take bug report"** → share the zip to Google Drive → download on PC.

---

## Phase 3 — Analyze in Wireshark

1. Install **Wireshark** (`https://www.wireshark.org`).
2. **Open** `btsnoop_hci.log` directly (Wireshark reads btsnoop natively).
3. **Filter to BLE writes** (the trigger is a write from phone → glasses):
   ```
   btatt.opcode == 0x52 || btatt.opcode == 0x12
   ```
   (`0x52` = Write Command / write-no-response — what `6E400002` and `AE01` use; `0x12` = Write Request.)
4. **Find the repeating one.** Look for a write that appears **~3 times**, spaced ~15s apart, matching your photo presses. That payload is the prime trigger candidate.
5. **Confirm the destination characteristic.** Click the packet → expand **Bluetooth Attribute Protocol** → note the **Handle**. Cross-check the handle against our GATT map — we expect it to resolve to:
   - `6E400002` (Nordic UART RX — prime suspect), or
   - `0000AE01` (Jieli command channel).
   If Wireshark captured the service discovery it shows the UUID directly; otherwise match by handle.
6. **Record the bytes.** Copy the `btatt.value` (e.g. something like `01 0A 00 …`). Note: characteristic UUID, handle, full hex payload, and whether a Notify came back on `6E400003`/`AE02` right after (the device's ack/response).

What you're capturing per press, to log:
| Field | Example |
|---|---|
| Target characteristic | `6E400002` |
| Handle | `0x0014` |
| Write opcode | `0x52` (no response) |
| Payload (hex) | `__ __ __ …` |
| Response notify? | yes/no, on which char, payload |

---

## Phase 4 — Confirm by replay (nRF Connect)

Prove the captured bytes actually fire the camera:

1. Install **nRF Connect for Mobile** (phone). **Close HeyCyan** and disconnect it from the glasses first (single client at a time).
2. In nRF Connect: **Scan → connect to `AIMB-G2_A034`**.
3. Expand the target service (`6E40FFF0…` or `AE30`), find the write characteristic (`6E400002` / `AE01`).
4. Tap the **write (↑)** icon, set type to **WRITE COMMAND** (no response) if that's what the capture showed, paste the **hex payload**, **Send**.
5. **Watch/listen:** if the glasses take a photo (shutter sound / LED / a new file appears on next sync), the trigger is **confirmed**. ✅
6. If nothing happens, try the other candidate characteristic, or check whether the app sends a short **handshake/enable** write first (replay that, then the trigger).

---

## After capture

- **Disable** Bluetooth HCI snoop log in Developer Options (it logs everything — privacy + performance).
- **Record results** in `Device_Recon_Record.md` as *Session 02*: the confirmed trigger char + payload, ack behavior, and whether replay worked.
- **Next:** the *transfer* half — trigger a photo sync in HeyCyan, watch the glasses raise their WiFi AP, then port-scan / sniff how the image file comes across.

---

## Troubleshooting
- **adb says `unauthorized`:** unlock phone, accept the USB-debugging prompt; re-run `adb devices`.
- **Snoop log empty / not updating:** you didn't toggle Bluetooth off/on after enabling it — do that, then recapture.
- **Too much traffic to read:** the spaced 3-press pattern is the trick — ignore anything that doesn't repeat at your press times. Also try filtering by the glasses' handle range once known.
- **Can't tell trigger from sync:** trigger = tiny write (a few bytes) at the moment of press; sync/transfer = large/sustained traffic (and on WiFi, not in this BLE log) — they look very different.
- **No write appears at all:** the capture (shutter) may be a *local button* handled on-device, and only the *download* crosses BLE/WiFi. If so, on-demand capture from our app may not be reachable over BLE — an important (if disappointing) finding to record.
