# Project Brief — "JARVIS" AI Companion for AIMB-G2 Smart Glasses

*Working codename: **ECHO** (placeholder — rename freely)*
*Version 0.1 · Living document · Last updated: 10 June 2026*

> **How to use this doc:** This is the single source of truth we build off of. It captures what the hardware actually is, what we're building, and in what order. It is deliberately honest about what's proven versus unproven. We update it as decisions get made and as the camera question resolves.

---

## 1. Vision — the north star

We are building a personal AI companion that **lives in your ear and sees through your eyes.** You talk to it; it answers in your ear; it can look at what's in front of you and reason about it; and everything it experiences flows into a personal memory it can recall on command.

The honest reframe of "Iron Man": the AIMB-G2 has **no display**, so there is no floating visual HUD painted over your vision — that is physically impossible on this hardware. But JARVIS was never really the HUD. JARVIS was the *voice* — a calm, capable intelligence you converse with that knows your context. That is exactly what this hardware is suited for, and it is what we are building.

The thing that makes our app unique is not any single feature. It is the **shared memory layer** underneath all of them — a personal "second brain" that every feature writes into and reads from, so the app behaves like one mind with many doors rather than a bag of disconnected gadgets.

---

## 2. The hardware reality

The AIMB-G2 (sold rebadged under several brands — Erilles, ROSE STAR, "G2 Pro," etc.) is built on cheap, off-the-shelf Chinese silicon. Key facts established through research:

- **Main controller:** Zhuhai Jieli Technology **JL7018F** chipset.
- **Camera:** ~8MP, 1080p/30fps video (camera chip is an Allwinner V821 on the closely related G1).
- **Audio:** Open-ear AAC speakers + noise-canceling microphone. Behaves as a **standard Bluetooth headset**.
- **Lenses:** Photochromic (color-changing) — *not* a display.
- **Connectivity:** Bluetooth (control + audio) and WiFi (photo/video file transfer).
- **Stock companion app:** "CyanGlasses" / "HeyCyan" — closed-source, talks to the glasses over an undocumented protocol.
- **No on-device app runtime.** Nothing we write runs *on* the glasses. All intelligence runs on the phone and in the cloud. The glasses are sensors + a speaker.

### Capability map — what we can reach, and how hard it is

| Capability | Path | Reachability | Notes |
|---|---|---|---|
| Microphone (your voice in) | Standard Bluetooth (HFP) | **Easy / proven** | Normal Android audio APIs. No proprietary protocol needed. |
| Speaker (voice out, in your ear) | Standard Bluetooth (A2DP) | **Easy / proven** | Normal Android audio APIs. |
| Camera stills / video | WiFi → vendor app, undocumented protocol | **Hard / unproven** | The make-or-break unknown. Needs a technical spike (see §6). |
| Physical temple buttons | Currently bound to stock app | **Medium / uncertain** | May be interceptable as media keys or via Jieli SDK; otherwise we use a wake word / on-screen control. |
| Firmware behavior on glasses | Jieli SDK (NDA, manufacturer-only) | **Out of scope** | We are not modifying the glasses themselves. |

**Design consequence:** We build audio-first. Anything needing a camera image is gated behind the camera spike.

---

## 3. Architecture — the three layers

```
  GLASSES (sensors + speaker)
        │  Bluetooth audio (mic in / voice out)  ── proven
        │  WiFi image transfer ─────────────────  ── unproven (spike)
        ▼
  ANDROID APP (the brain / orchestrator)
        │  - captures audio, manages Bluetooth
        │  - wake word / push-to-talk
        │  - routes requests to AI models
        │  - writes & queries the memory index
        ▼
  CLOUD (memory + intelligence)
        ├── Personal Memory Index  (vector DB + file storage)
        └── AI Models  (multimodal LLM, speech-to-text, text-to-speech, translation)
```

### The keystone: the Personal Memory Index

This is the idea that elevates the whole project. It is a **vector database** acting as a personal exocortex. Every meaningful event — a photo, a voice note, a meeting transcript, text read aloud, an answer the AI gave, with timestamp and location — is converted into an *embedding* (a fingerprint of its meaning) and stored.

When you later ask "where did I leave my passport?" or "what did Sam say about the budget?", we embed the question the same way and the database returns the closest matches *by meaning, not keywords*. This retrieval-augmented (RAG) pattern is what makes the second brain feel like it understands your life rather than just filing it.

**One index powers four of the five features.** The second-brain *is* the index. Meeting capture, read-it-to-me, and look-and-ask all write into it. They stop being separate apps and become one memory with different entry points.

**Recommended backbone:** Supabase (already connected) — Postgres + `pgvector` for vector search, file storage for images/audio, and user auth, all in one ownable place rather than a black-box service.

---

## 4. Feature set (in build-priority order)

Priorities set by the project director. Most features reuse the same core pipeline — *capture → (optionally look) → ask AI → speak the answer → write to memory* — so building the first gets us most of the way to the rest.

1. **Visual Second-Brain** *(top priority, also the keystone)* — Capture moments (photo + voice tag + time + place) into the index; recall them later by natural-language question. "Where did I park?", "what was that wine I liked?" *Depends on the camera spike for the visual half; the voice-note half works immediately.*
2. **Look & Ask** — Wake word or button → grab a camera frame → multimodal AI identifies/explains/answers → speaks back. The flagship "magic" moment. *Depends on camera spike.*
3. **Read-it-to-me (OCR)** — Point at a document, label, or sign; hear it read aloud. High accessibility value. *Depends on camera spike.*
4. **Meeting Capture + Summary** — Record audio, transcribe, summarize, extract action items into the index. *Audio-only — fully buildable now.*
5. **Translation** — Two modes: (a) signs/menus (snap → OCR → translate → speak — robust), and (b) live conversation (mic → translate → speak). *Caveat: the noise-canceling mic is tuned to the wearer, so translating a stranger facing you is the weak case; the menu/sign mode is solid.*

### Additional possibilities on the table (audio-first + camera + index)

- Passive life-logging / auto-journal (searchable timeline of your day)
- Receipt & expense capture → spreadsheet export
- Hands-free dictation & messaging with visual context
- Location-aware audio tour guide
- Spaced-repetition learning (turns your day into flashcards)
- Accessibility suite (scene narration, currency/color ID) — arguably this hardware's *best* use case
- Proactive nudges ("you mentioned calling the dentist — remind you at 5?")

---

## 5. The two memory streams — what's solid vs. what's a risk

This distinction governs the whole roadmap, so it gets its own section.

- **Audio / text stream — SOLID.** Meeting transcripts, voice notes, spoken Q&A, speech translation. Flows through standard Bluetooth audio, which we know works. A genuinely powerful audio-and-text exocortex is buildable today with zero hardware uncertainty.
- **Visual stream — ~~UNPROVEN~~ PROVEN REACHABLE (10 Jun, Sessions 02–03).** ~~Every feature needing an actual image depends on getting camera frames out of the glasses…the single biggest project risk.~~ **Both halves are now demonstrated on real hardware:** (a) **capture on demand** via a sniffed BLE command (`BC 41 03 00 10 50 02 01 01` → camera endpoint, Session 02), and (b) **image transfer** off the glasses via **Wi-Fi Direct** (phone = P2P group owner `192.168.49.1`, glasses client `192.168.49.115`; on-demand, tears down after — Session 03). The feasibility risk is **retired**. What remains is *protocol* work — reverse-engineering the exact transfer port/protocol so our own app can pull the bytes (next step: static analysis of the `com.aitowe.aitoglasses` APK). See `Device_Recon_Record.md` §Sessions 02–03.

---

## 6. Build roadmap

The strategy: make progress on the certain things while resolving the uncertain one, so we are never blocked.

### Phase 0 — Foundation & the camera spike *(do these in parallel)*
- **Camera spike (highest-risk-first):** Investigate whether a third-party Android app can obtain camera images. Approaches to test: observe where the stock app deposits files on the phone (a readable folder?), and/or sniff the WiFi traffic between glasses and stock app to understand the transfer protocol. **Exit criteria:** a clear yes/no on (a) can we get an image, (b) can we get it on demand. This decision unblocks features 1–3.
- **Cloud index stand-up:** Provision Supabase, schema for memories (embedding, media reference, transcript, timestamp, location, tags), wire up vector search.
- **Audio pipeline:** Connect to the glasses as a Bluetooth headset; prove mic capture and speaker playback from our own app.

### Phase 1 — First working loop (audio-only)
- **Meeting Capture + Summary** end-to-end: record → transcribe → summarize → store in index → recall by voice. This proves the entire pipeline (capture → AI → memory → retrieval) with no camera dependency.

### Phase 2 — Add vision *(conditional on Phase 0 spike result)*
- If camera access is **green:** build Look & Ask and Read-it-to-me on the proven pipeline, then layer the visual half of the Second-Brain on top.
- If camera access is **red:** pivot to workarounds (e.g., manual import of synced photos) and weight the roadmap toward the rich audio/text features, which are already strong.

### Phase 3 — Polish & expand
- Translation modes, proactive nudges, and selected items from the "additional possibilities" list.

---

## 7. Tech stack (working assumptions)

- **Platform:** Android first (decided).
- **App language:** Kotlin (modern Android standard).
- **Memory index / backend:** Supabase — Postgres + `pgvector`, storage, auth.
- **AI models:** A multimodal LLM for vision + reasoning; a speech-to-text service; a text-to-speech voice; a translation capability (often the same LLM). Specific providers to be chosen in Phase 0. *Data point (Session 04): the stock app uses **Microsoft Azure Speech** for STT/TTS/keyword-spotting/translation, plus on-device **TFLite** intent models for wake/intent. Not a mandate for us, but it shows what the OEM found workable on this hardware.*
- **Bluetooth:** Standard Android audio APIs for mic/speaker; Jieli RCSP SDK considered only if we need button events or device settings.

*All provider choices are revisable; nothing here locks us in.*

---

## 8. Open decisions & risks

| Item | Status | Owner |
|---|---|---|
| Can we access the camera, on demand? | **PROVEN (10 Jun, Sessions 02–03)** — trigger (BLE command) + transfer (Wi-Fi Direct) both demonstrated on hardware. Remaining = protocol RE (APK static analysis), not feasibility. | Build |
| Privacy stance (see below) | **Needs a decision** | Director |
| AI provider selection (LLM/STT/TTS) | Open — Phase 0 | Build |
| Wake word vs. button vs. on-screen trigger | Open — pending button-access test | Build |
| Project name (codename "ECHO" is a placeholder) | Open | Director |

**Privacy — decide early, it shapes the design.** Two tempting features are legal/ethical minefields: **face/person recognition** ("who is this again?") and **always-on recording of others** (recording-consent law varies by region). Recommended stance: build only *consensual* versions — your own meetings, people you log with permission — and steer hard away from anything that identifies or records non-consenting people. This needs the director's explicit sign-off before any related feature is built.

---

## 8b. Live device recon — findings (10 June 2026, Windows PC over Bluetooth)

First hands-on probe of a real unit, run from a Windows PC with the glasses Bluetooth-paired to it (WinRT BLE + audio-endpoint enumeration). **These are empirical, not research.**

### Confirmed GREEN — Audio path
The glasses enumerate as a standard Bluetooth headset with **both** profiles live and `Status: OK`:
- `Headset (AIMB-G2_A034 Hands-Free)` → **HFP** (mic in) ✅
- `Headphones (AIMB-G2_A034)` → **A2DP** (voice out) ✅

This empirically validates §6's audio pipeline. The audio-first roadmap has **zero hardware uncertainty** — confirmed on hardware, not just assumed.

### Mapped — BLE control surface (GATT)
Full service + characteristic map pulled off the device. Standard services (1800/1801/180A) omitted except Device Info.

| Service UUID | Characteristics (properties) | Read as |
|---|---|---|
| `6E40FFF0-…-24DCCA9E` | `6E400002` (Write/WriteNoResp), `6E400003` (Notify) | **Nordic UART Service clone** — generic serial command pipe. **Prime suspect for the main HeyCyan app↔glasses command channel.** |
| `0000AE30` (Jieli/JL7018F custom) | `AE01` (WriteNoResp), `AE02` (Notify), `AE03` (WriteNoResp), `AE04` (Notify), `AE05` (Indicate), `AE10` (Read/Write) | Jieli proprietary service. Paired write/notify channels = command+response / data+ack. Likely RCSP-style control, large-data (file/OTA), `AE10` = config register. |
| `0000AE3A` | `AE3B` (WriteNoResp), `AE3C` (Notify) | Secondary Jieli command/notify pair. |
| `DE5BF728-…-012A5DC7` | `DE5BF72A` (Write/WriteNoResp), `DE5BF729` (Notify) | Custom 128-bit serial pipe — second data channel (purpose TBD). |
| `00003802` | `00004A02` (Read/Write/Notify) | Vendor data/control characteristic. |
| `0000FEE1` | `0000FEE3` (Read/Write/Notify) | Likely OTA / control. |
| `0000180A` Device Info | Serial / HW rev / FW rev / System ID (Read) | Values not decoded yet (PS↔WinRT buffer limitation; use Python `bleak` or nRF Connect to dump). |

**Why this matters — it reframes the camera spike.** The capture trigger is almost certainly a **short BLE command** written over one of these pipes (likely `6E400002` or `AE01`); the resulting image then transfers over **WiFi**. So the camera question splits into two independently-sniffable halves:
  1. **Trigger** — the BLE bytes the app writes to make the glasses take a photo (capture on demand → answers §6's *hard* half). Reverse-engineer by sniffing what HeyCyan writes.
  2. **Transfer** — the WiFi file pull of the resulting image.
If we can replay the trigger command from our own app, "capture on demand" becomes feasible without the Jieli NDA SDK. ~~This is a promising lead, not yet proven.~~ **UPDATE (Session 02, 10 Jun): the trigger half is PROVEN** — the exact BLE capture command was sniffed and timing-confirmed (`BC 41 03 00 10 50 02 01 01` → handle `0x008E` on a separate camera LE endpoint). On-demand capture over BLE is feasible without the NDA SDK. Only the WiFi image-*transfer* half remains open. See `Device_Recon_Record.md` §Session 02.

### Finding — WiFi is on-demand, not always-on
With the glasses idle (paired to PC, no stock app driving them), they broadcast **no WiFi AP** and appear on **no LAN**. Strong evidence the WiFi/camera radio only wakes when the stock app commands it over BLE. Consequence: the camera spike **must** be driven from the phone (where HeyCyan + the BLE link live); the PC can assist by joining/sniffing the glasses' AP once it appears, but cannot trigger it.

### Tooling note
Reusable probe script saved at `Jarvis Glasses/ble_probe.ps1` (WinRT, no external deps; maps services/characteristics). To dump readable *values* and to sniff/write the control channels, the cleaner toolchain is **Python + `bleak`** (cross-platform GATT) or **nRF Connect** (phone) — recommended for the next BLE session.

### Full camera pipeline — REVERSE-ENGINEERED (Session 04, APK static analysis)

The complete on-demand image path is now specified (decompiled stock app `com.aitowe.aitoglasses`):

1. **BLE control plane** = vendor SDK **`com.oudmon.ble`** (`LargeDataHandler.glassesControl(byte[])`). Triggers capture (Session 02's `BC 41…`) and starts the WiFi transfer (e.g. `glassesControl({0x02,0x01,0x0F})`).
2. **Transport** = **Wi-Fi Direct**; glasses report their WiFi IP back to the app **over BLE**.
3. **Protocol** = **plain HTTP, the glasses are an HTTP server on port 80**:
   - `GET http://<glassesIP>/files/media.config` → manifest of media to import
   - `GET http://<glassesIP>/files/<filename>` → each photo/video (saved to DCIM)

**No vendor cloud is involved in the image path** — it's HTTP over the local P2P link. To build our own: BLE start cmd → Wi-Fi Direct → read IP from BLE → HTTP GET `/files/…`. **Key dependency: the `com.oudmon.ble` SDK** (obtain or reimplement its `glassesControl` framing). Full spec in `Device_Recon_Record.md` §Session 04.

---

## 9. Glossary

- **Embedding** — a numeric fingerprint of meaning; lets us search by concept, not exact words.
- **Vector database** — stores embeddings and finds the closest matches to a query.
- **RAG (retrieval-augmented generation)** — fetch relevant memories first, then have the AI answer using them.
- **HFP / A2DP** — standard Bluetooth profiles for headset mic and audio playback.
- **RCSP** — Jieli's proprietary protocol; their official SDKs use it for device control and firmware updates.
- **Spike** — a short, focused experiment to answer one risky technical question before committing.

---

*Next action on deck: kick off the Phase 0 camera spike while standing up the cloud index and audio pipeline. Update this doc as the spike resolves.*
