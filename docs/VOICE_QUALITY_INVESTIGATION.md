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

## Findings (fill in)
- _(record the evidence + the named dominant failure mode here before building any fix)_
