# Session log — 2026-06-13 (voice quality → conversation → on-device STT → memory v1 → agent plan)

*A thorough record of everything done in this (long) session, the current state, open blockers, and
the planned next steps. Companion to `SESSION_HANDOFF.md` (canonical handover). Written because the
session context filled up; the next session starts fresh from the handoff prompt + this log.*

## What was accomplished (in order)

### 1. Voice-conversation quality investigation → fix (DONE + verified)
- Built debug instrumentation (per-turn WAV + `index.tsv` + `EchoVoice`/`EchoLatency` logs;
  `scripts/analyze_wav.mjs` spectral analyser). Ran scripted voice turns with the director.
- **Named dominant failure mode = VAD endpointing** (not the mic, not STT). Mic is **mSBC wideband
  16 kHz** (BT-stack log) — hypothesis #1 refuted. Premature endpointing (`silenceMs=700`) cut speech
  off; one-shot 250 ms noise calibration missed speech; the "listening" earcon never reached the ear.
- **Fixed `BtAudio.recordUntilSilence`:** trailing silence 700→1500 ms, robust rolling noise floor,
  speech-start debounce, SCO-hot warm-up flush, and an **audible cue over the SCO route** (the old
  A2DP ToneGenerator earcon never reached the ear). Silence guard in `doTalk` so STT hallucinations on
  silence never reach the LLM. **Verified:** counting 1→10 with pauses + a 14 s sentence with stops
  captured whole. Docs: `docs/VOICE_QUALITY_INVESTIGATION.md`.

### 2. Conversation mode (DONE + verified)
- **Turn-taking:** orb + "Jarvis" wake word now start a **continuous conversation**
  (`HomeViewModel.converse(continuous)`); glasses button stays one-shot. After answering, the mic
  re-opens for a follow-up until the user goes quiet (one "still there?" reprompt) or says a closing
  phrase (`isClosing()` — robust to "thank you bye" / "by"/punctuation).
- **Ending:** "End conversation" button + orb re-tap (the orb was `enabled=!busy` so it was disabled —
  fixed); responsive cancel (`recordUntilSilence(shouldAbort)` + `TtsEngine.stop()` unblocks).
- **Multi-turn context:** threads the last 3–6 turns via `ChatRequest.history` (backend already
  supported it). **Scrollable on-screen transcript** (`vm.transcript`).
- **Barge-in (WORKS):** talk over JARVIS and it stops. Held SCO full-duplex during the answer; AEC
  cancels echo to ~50 RMS; the fix that cracked it was a **hangover counter** (not consecutive frames)
  + low floor (`awaitBargeIn`). Verified firing cleanly with no false trips.

### 3. On-device STT (DONE + verified) — the quota fix
- **Root cause of "it can't hear me":** the Gemini free-tier **daily** STT quota (~20/day) ran dry
  mid-session (429 RESOURCE_EXHAUSTED); the app silently showed quota 502s as "didn't catch that".
- **Fix = on-device STT via sherpa-onnx (offline Whisper-tiny.en):** primary transcriber, ~0.4–0.5 s
  per turn, no network/quota. Model **downloads on first run** (~103 MB → filesDir). Cloud STT kept as
  fallback. Slim AAR (arm64-v8a) at `android/device/audio/libs/sherpa-onnx-1.13.2-arm64.aar`;
  `SherpaStt.kt`. Director: accuracy "fairly good." Research: `docs/STT_FAILOVER.md`.

### 4. Assistant Memory v1 (DEPLOYED to prod + verified) — the big strategic win
Hermes/OpenClaw memory pattern (research: `docs/ASSISTANT_MEMORY.md`). A truthful, self-knowing
foundation toward JARVIS as a **chief of staff**.
- **v1.1 profile layer:** `profile` table (SOUL + curated user_facts) injected into `chat`/`chat-stream`
  with a **non-negotiable TRUTH charter** (never fabricate; "I don't know" then go find out). SOUL
  seeded from `docs/assistant/SOUL.md` (director to edit). **Verified live:** in-character chief-of-
  staff intro + admits ignorance instead of fabricating.
- **v1.2 distillation** (`distill` fn): extracts durable facts from a finished conversation into the
  profile instead of hoarding raw Q&A. App fires it at conversation end. Verified.
- **v1.3 explicit memory** (`remember` fn): "remember that…" pins a fact instantly ("Noted").
- **v1.4 self-improvement:** covered by the distill correction-merge.
- **v1.5 editable profile:** Settings → "JARVIS's memory" (view/edit SOUL + facts).
- Migration + 6 functions deployed (director-authorized). Roadmap: `docs/ASSISTANT_ROADMAP.md`.
- ⚠️ **Data caution:** during testing, a `user_facts=""` clear wiped the director's real distilled
  facts (Name: Lawrence; children Leroy/Keiko). Restoring them was (correctly) blocked — can't write
  unverified personal facts. **Director should re-add via Settings or by talking.**

### 5. Agent delegation design (DOCUMENTED) — the next big direction
- JARVIS delegates heavy tasks to **Claude Code on the director's Max subscription** (legitimate:
  running Claude Code the product on the sub, ≠ raw-API-via-sub). Fast voice lane = API; heavy lane =
  Claude Code. Dual approach: **Phase 1 local Agent Bridge** (`claude -p`) → **Phase 2 hosted + async**
  (FCM push). Capability order (director): **research → coding → email/calendar**. Full design +
  milestones M0–M4: `docs/AGENT_DELEGATION.md`.
- **M0 feasibility VERIFIED:** `claude -p "…" --output-format json` runs headless and returns clean
  JSON (tested: returned `BRIDGE_OK`). Ready to build the bridge.

### 6. Voice-controlled glasses skill (BUILT, BLOCKED on hardware)
- "what am I looking at" / "take a photo of this" → `reactor.captureAndDescribe()` (mirrors the
  AI-gesture). **Two hardware findings, both documented in `docs/recon/Glasses_Controls.md`:**
  1. **Camera is gated during a BT-audio conversation** — a capture sent while SCO/A2DP is up is ACK'd
     (`BC 41`) but no photo is taken (no `BC 73` CaptureSaved). Releasing SCO + settle isn't enough
     (A2DP is system-held). Decision pending: full audio drop vs button-then-discuss.
  2. **Glasses firmware wedged after heavy testing:** by session end, the glasses stopped sending the
     *entire* `BC 73` event channel and the **front-button capture stopped working** (a firmware-level
     function, app-independent) — then **no buttons responded at all.** Likely battery-depleted/frozen.
     **Director is charging them; needs a charge + hard reset** to recover. The app/our code cannot
     cause a firmware-capture failure — this is the glasses hardware.

## Current state
- **Live + working:** voice-quality fix, conversation mode (turn-taking/ending/transcript/barge-in),
  on-device STT, Assistant Memory v1 (prod), all installed on the Pixel (prodDebug).
- **Blocked:** all glasses-capture features (voice-vision, AI-gesture, sync) — **glasses hardware
  wedged/charging; needs a hardware reset.** Independent of software.
- **Ready to build:** Agent Bridge M0 (claude -p verified headless).

## Open items / next steps
1. **Agent work (director's priority): build M0** — a local Agent Bridge (Node) wrapping
   `claude -p "<task>" --output-format json` with `{prompt, cwd?, allowedTools?, timeoutMs?}`; run on
   this PC; test with a **research** prompt via curl. Then M1 (app dispatches "Jarvis, research…" via
   `adb reverse`, speaks the summary, distills into memory). Per `docs/AGENT_DELEGATION.md`.
2. **Glasses battery gauge (director ask):** show the glasses' battery level in the app. **Not yet
   exposed in our BLE impl** — recon (`01_IMPLEMENTATION_PLAN.md`, `Transfer_Protocol.md`) notes an
   "oudmon" device-info/**battery** command exists in the protocol but it's **unimplemented**. Need to
   decode/issue the battery-read command (BLE) and surface it. (Would also have pre-empted the
   wedge — low battery disables the camera + flakes BLE.)
3. **Glasses recovery:** after charge + hard reset, test the **front-button photo first** (baseline),
   then AI-gesture, then voice-vision. If healthy, revisit the camera-vs-conversation-audio decision.
4. **Real BLE bug to fix:** auto-reconnect doesn't re-enable notifications (a dropped GATT link goes
   silently deaf to events until app restart) — make `GlassesBleManager` re-subscribe on every reconnect.
5. **Director to edit `docs/assistant/SOUL.md`** (re-seed via `profile` fn or Settings) + re-add the
   wiped user_facts.

## Key commits (this session, on `master`, pushed)
Voice quality `5fb3986`/`48bb372`; conversation `6e9a324`→`62fd991` (barge-in fix `62fd991`); on-device
STT `3d8d6e0`; memory v1 `dba3231`/`7bfde7e`/`1733cf2`; SOUL/roadmap `eb6efd1`; agent design `bde314b`;
voice-vision + glasses recon `3af3cde`→`a9fd689`. (HEAD is the silent-settle vision commit.)

## Gotchas discovered this session (also in handoff/recon)
- Don't test memory cleanup against the **live** profile (wiped real facts).
- Gemini free-tier STT daily quota is tiny (~20/day) — on-device STT is the answer.
- Barge-in needs full-duplex SCO → conversation answer audio is narrowband (accepted).
- Glasses camera can't run during a BT-audio conversation (firmware).
- Heavy back-to-back testing wedges the glasses' Bluetooth/firmware — charge + reset to recover.
- `claude -p --output-format json` is the headless agent engine (verified).
