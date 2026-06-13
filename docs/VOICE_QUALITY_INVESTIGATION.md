# Voice-conversation quality — investigation brief

*Opened 2026-06-13. The director reports JARVIS "often doesn't pick up what I'm saying and makes
too many errors" in spoken use. This doc frames a structured root-cause analysis. **Diagnose before
fixing** — name the dominant failure mode with evidence first. Record findings inline as you go.*

---

## The voice pipeline (where each stage lives)

A spoken turn (`HomeViewModel.doTalk()` in `:app`) runs:
1. **Earcon** — `audio.earcon(LISTENING)` (`:device:audio/BtAudio.kt`).
2. **Record + endpoint** — `audio.recordUntilSilence()` records the **glasses mic over Bluetooth
   SCO (HFP)** and stops ~0.7 s after speech ends (VAD). Returns PCM + sampleRate.
   - SCO setup costs ~1.5 s up front; baseline (no-speech) was record≈6.6 s.
3. **STT** — `WavUtil.pcm16ToWav(...)` → `backend.transcribe(wav)` → the **`transcribe` Edge
   Function** → **Gemini `gemini-2.5-flash`** (multimodal audio). Returns text.
4. **Blank guard** — empty transcript → "Didn't catch that — try again".
5. **Answer (RAG)** — `streamedChat(heard)` (FULL tier) / `backend.chat(heard)` → the **`chat` /
   `chat-stream` Edge Function**: embed → `match_memories` (pgvector) → Claude `claude-sonnet-4-6`
   grounded in the user's memories. Streams sentences.
6. **TTS** — Android on-device TTS speaks (sentence-by-sentence on FULL tier), routed to the
   glasses speaker (A2DP).

**Instrumentation already present:** each turn logs to logcat tag `EchoLatency`:
`record=<ms> (<s>s audio) stt=<ms> llm=<ms> streamed=<bool> time-to-speak=<ms>`.

---

## Ranked hypotheses (most→least likely, to confirm/refute with evidence)

1. **Bluetooth SCO mic is narrowband / low quality.** HFP SCO is often 8 kHz CVSD (telephone
   quality) — brutal for ASR. If the glasses negotiate narrowband, transcription will be error-prone
   no matter how good Gemini is. **Check the actual sample rate + codec of the recorded audio.**
2. **Endpointing cuts speech off.** `recordUntilSilence()` VAD may stop on a mid-sentence pause, so
   STT only hears a fragment. **Check recorded duration vs. how long the director actually spoke;
   listen to the saved WAVs for truncation.**
3. **STT mishears even on clean audio.** Gemini 2.5-flash audio transcription quality / prompt /
   format. **Compare the WAV (listen) to the returned transcript.**
4. **SCO warm-up clips the start.** The ~1.5 s SCO setup may swallow the first word if recording
   starts before the link is hot.
5. **Understanding/RAG, not hearing.** Transcript is correct but the answer misses because recall
   retrieves the wrong memories or the prompt is weak. **Only relevant once 1–4 are ruled out.**
6. **Environment** — noise, distance, mic placement on the frames.

---

## Method — capture ground truth (don't guess)

The fix is to **collect real turns with all three artifacts** and compare:
- **The audio** JARVIS actually recorded (save the WAV — add a debug dump in `doTalk`/`BtAudio`).
- **The transcript** Gemini returned (`question` / logcat).
- **What the director actually said** (ground truth, written down per turn).

Suggested steps:
1. Add a temporary debug switch that **saves each turn's WAV** to a retrievable path (or upload to
   the `audio` bucket) so you can *listen* to exactly what the mic captured.
2. Run ~10 scripted phrases (short, long, with pauses, numbers, names). Log `EchoLatency` + the WAV
   + the transcript for each. Note the ground truth.
3. Score: audio intelligible to a human? transcript matches audio? matches ground truth? answer
   correct given transcript? This isolates which stage is failing.
4. Inspect the SCO format: log `rec.sampleRate` and the `AudioFormat` in `BtAudio.recordUntilSilence`;
   check logcat for `SCO_WB` (wideband) vs narrowband during a turn.

## Key facts / gotchas for this investigation
- The mic is the **glasses** over SCO whenever they're powered on (the phone mic is only used for
  the Vosk wake word). Test with the glasses worn normally.
- A live **recording on the glasses** (hold-BACK) changes button behavior and ties up audio — make
  sure nothing is recording.
- `EchoLatency` baseline: record≈6.6 s (1.5 s SCO + VAD) · stt≈3.7 s · llm≈5.1 s (no-speech run).
- Code: `:device:audio/BtAudio.kt` (`BtAudioEngine.recordUntilSilence`, VAD thresholds), `:app/
  HomeViewModel.doTalk`, `supabase/functions/transcribe` (Gemini call + prompt).

---

## Instrumentation added (2026-06-13, debug builds only — temporary)

Clearly-labeled, behaviour-neutral diagnostics so each turn yields the three comparable artifacts.
**All gated on `BuildConfig.DEBUG`; the release path is unchanged. Remove once the dominant failure
mode is named.**

- **`:device:audio/BtAudio.kt`** — `Recording` now carries VAD diagnostics: `stopReason`
  (`trailingSilence` | `maxMs` | `noSpeechTimeout` | `fixed`), `noiseFloor`, `threshold`,
  `speechStarted`. `recordUntilSilence()` populates them.
- **`:app/HomeViewModel.doTalk()`** — `dumpVoiceDebug()` writes, per turn:
  - the exact mic **WAV** → `…/files/voicedbg/turn_<ms>.wav`
  - an **`index.tsv`** row: `ts · secs · sampleRate · peak · rms · noiseFloor · threshold ·
    stopReason · speech=<bool> · rec=<ms> · stt=<ms> · transcript`
  - an **`EchoVoice`** logcat line with the same fields.
- **`scripts/analyze_wav.mjs`** — PC-side Node FFT (no deps). Reports per-WAV energy split
  (<3.4 kHz / 3.4–4 kHz / >4 kHz), the effective spectral cutoff, and a NARROWBAND/WIDEBAND
  verdict. **Self-tested** against synthetic 3 kHz-limited vs 7 kHz signals (correct verdicts).
  This is the definitive test for hypothesis #1 — the WAV header always says 16 kHz (the app
  hardcodes `AudioRecord` at 16 kHz) so only the spectrum reveals narrowband CVSD.

### How to run a capture session (when the director is wearing the glasses)

```powershell
# clean slate + watch the per-turn diagnostics live
adb shell rm -rf /sdcard/Android/data/com.echo.companion/files/voicedbg
adb logcat -c
adb logcat -s EchoVoice EchoLatency           # leave running while we do turns
# (separate window) watch the BT SCO codec the moment a turn records:
adb logcat | Select-String -Pattern "SCO|mSBC|CVSD|WBS|swb|codec_id|BtHfp"

# …do the scripted turns (below)…

# pull + spectral-analyse every captured WAV
adb pull /sdcard/Android/data/com.echo.companion/files/voicedbg ./voicedbg
adb shell cat /sdcard/Android/data/com.echo.companion/files/voicedbg/index.tsv
node scripts/analyze_wav.mjs ./voicedbg
```

### Scripted phrase set (director reads these = ground truth)

Run each as one voice turn; note any that felt cut off. Designed to separate the failure modes:

1. `What time is it?` — short, common words (baseline)
2. `Remember that I parked on level three.` — memory write + a number
3. `What did I say about the dentist appointment?` — recall query
4. `I need to buy milk … and also call my mother.` — **deliberate ~1 s pause** at the `…`
   (tests `silenceMs=700` mid-sentence cut-off, hypothesis #2)
5. `My flight is B A two seven five at gate forty two.` — alphanumerics (STT stress)
6. `Tell Aoife and Søren about the dinner on Friday.` — proper nouns / non-English names
7. A long ~20-word sentence said in one breath (tests sustained capture + `maxMs=12 s`)
8. The same short phrase said **quietly**, then **at normal volume**, then **loudly**
   (tests level/threshold calibration, hypotheses #1/#4)
9. Start speaking **immediately** on the earcon (tests SCO warm-up clipping the first word, #4)
10. One phrase in a **noisier** spot vs a quiet room (environment, #6)

### Scoring (per turn) — isolates the failing stage

| Audio intelligible to a human? | Transcript matches the audio? | Transcript matches ground truth? | ⇒ Dominant failure |
|---|---|---|---|
| No (muffled/cut/silent) | — | — | **Mic/SCO or endpointing** (check verdict + `stopReason`) |
| Yes | No | No | **STT** (Gemini mishears clean audio) |
| Yes | Yes | Yes | hearing is fine → look at **RAG/answer** (#5) |

---

## Findings

### Preliminary (code + live device, before any voice turn) — 2026-06-13
- **The pipeline is blind to its own mic quality.** `BtAudioEngine` hardcodes `AudioRecord` at
  16 kHz and `WavUtil` stamps 16 kHz into every WAV header. If the SCO link negotiated narrowband
  CVSD (8 kHz), the captured PCM is upsampled 8 kHz content but the file still *claims* 16 kHz — so
  the container can't reveal it. **Only spectral analysis (or the BT codec log during a live SCO)
  can.** → motivates `analyze_wav.mjs` + the live-codec logcat.
- **Live `dumpsys bluetooth_manager`:** glasses `AIMB-G2_A034` are the **active HFP + A2DP** device;
  a recent SCO session was visible (`HeadsetService … AudioOn … SCO_VOLUME_CHANGED`), but the dump
  does **not** expose the SCO codec at rest. The narrowband-vs-wideband question is unresolved until
  a live turn — **hypothesis #1 remains the prime suspect, not yet confirmed.**
- **Three concrete endpointing risks in `recordUntilSilence()`** (hypotheses #2/#4):
  - noise floor calibrated from only the **first 250 ms** — i.e. during SCO ramp-up; a bad
    calibration mis-sets the threshold for the whole turn.
  - `silenceMs = 700` ms → a natural mid-sentence pause >0.7 s ends the turn early.
  - `noSpeechTimeoutMs = 4000` ms after a fixed **1.5 s** SCO warm-up → a first word landing in the
    warm-up window can be clipped or missed.

### Confirmed dominant failure mode
- _(fill in after the capture session — name it with the WAV verdicts + `index.tsv` + ground truth
  before building any fix)_
