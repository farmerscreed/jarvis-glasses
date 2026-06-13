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

### Capture session 1 — 2026-06-13 (prodDebug / cloud, director wearing the glasses)

12 real turns captured (`voicedbg/index.tsv` + WAVs + `EchoVoice` logcat + BT-stack log + spoken
ground truth). The evidence is decisive.

**Mic codec — hypothesis #1 (narrowband) DEFINITIVELY REFUTED.** The Bluetooth HFP stack log during
SCO setup: `bta_ag_sco_open: sco_codec = 2` (2 = mSBC), `HeadsetStateMachine … hasWbsEnabled=true`,
`SetScoConfig … mode: SCO_WB … bt_wbs=on`. The glasses mic negotiates **mSBC wideband (16 kHz)**, not
CVSD telephone. Captured levels are healthy whenever the director actually spoke (peak 5 000–10 900
of 32 767). *Caveat: `analyze_wav.mjs`'s "effective cutoff" reads "NARROWBAND" on quiet/short real
speech — an artifact of speech's natural HF roll-off + the 5%-of-peak threshold, NOT a codec limit.
The BT-stack log is authoritative; trust it over the FFT cutoff for real speech.*

**Turn-by-turn (ground truth → transcript, key fields):**

| GT phrase | transcript returned | speech? · stop · secs | what happened |
|---|---|---|---|
| What time is it? | "Trees that I mean to look you to your car." | false · noSpeechTimeout · 4.0 | silence (no beep cue) → **STT hallucinated** |
| (silence) | "sit" | false · noSpeechTimeout · 4.0 | silence → STT hallucinated |
| What time is it? | "is it. What time is it?" | true · trailingSilence · 4.6 | onset artifact "is it." (SCO warm-up) |
| What time is it? | "What time is it? What time is it?" | **false · noSpeechTimeout** · 4.0 | audio HAD speech but **VAD missed it** (noiseFloor 1048→thr 2500) |
| Remember that I parked on level three. | "Remember that I packed on level three." | true · trailingSilence · 3.0 | **good** — only "parked→packed" STT slip |
| My flight is BA 275 at gate 42. | "My flight is BA275.My flight is BA275." | true · trailingSilence · 3.9 | **lost "at gate forty two"** — cut on the pause |
| (flight retry) | "My flight is" | true · trailingSilence · 1.3 | **cut after "is"** |
| (flight retry) | "So, sell." | true · trailingSilence · 1.1 | cut / garbled |
| Tell Aoife about dinner on Friday. | "Oi fe." | true · trailingSilence · 1.8 | **cut after the name; everything else lost** |
| I need to buy milk and call my mother. | "Buy milk and call my mother." | false · noSpeechTimeout · 4.0 | **lost "I need to" onset**; VAD missed speech (thr 2500) |
| One two three four five. | "one" | true · trailingSilence · 1.1 | **cut after "one"** |
| (count retry) | "two three four" | true · trailingSilence · 3.0 | **cut before "five"** |

### ⇒ DOMINANT FAILURE MODE: the VAD endpointer (`recordUntilSilence`), not the mic, not STT.

The energy-based VAD fails in **two** directions, both visible above:

1. **Premature endpointing cuts speech off mid-utterance (the primary quality killer).** `silenceMs =
   700` ms ends the recording on any natural inter-word/inter-clause pause, and **the rest of the
   utterance is never recorded → permanent data loss.** Proven: "…at gate forty two", "…about dinner
   on Friday", and "…five" were all spoken but never captured; "One two three four five" was split
   into "one" / "two three four". This is exactly the director's own diagnosis ("the time is too
   short… it cuts you off while you're still speaking").
2. **Fragile one-shot noise calibration misses speech entirely.** The noise floor is sampled only in
   the first 250 ms (during SCO warm-up, sometimes while the user is already talking). A high reading
   caps the threshold at 2 500, so real speech frames never cross it → `speech=false` →
   `noSpeechTimeout`. Two turns whose audio plainly contained speech (Gemini transcribed it) were
   declared speechless this way.

**Compounding factors (real, secondary):**
- **The "listening" earcon never reaches the glasses** — director confirmed *no beep on any of the
  12 turns*. The A2DP output path works (spoken answers are audible), so it's specifically
  `BtAudioEngine.earcon()` (`ToneGenerator` on `STREAM_MUSIC`) not routing to the glasses. With no
  audible cue the user can't time speech to the uncued window → silence/onset-clip turns.
- **Blind 1.5 s SCO warm-up clips word onsets** — "I need to…"→"Buy milk…", the "is it." prefix.
- **STT hallucinates on silence** — empty/near-silent audio returns confident fabrications ("Trees
  that I mean to look you to your car", "sit") instead of blank, so the `heard.isBlank()` guard never
  fires and JARVIS answers a question that was never asked. A meaningful "makes too many errors"
  driver layered on top of the VAD failures.

**What is NOT the problem:** the Bluetooth mic (wideband mSBC, healthy levels) and Gemini STT on
complete audio (near-perfect on the one clean full-length turn: "Remember that I parked on level
three" → one-word slip). Hypotheses #1 and #3 are refuted; #5 (RAG) was never reached because hearing
fails first.

### Fix direction (for the NEXT session — do not build until director signs off)
Rework endpointing in `recordUntilSilence`: (a) much longer/adaptive trailing-silence (≈1.5–2.5 s,
or a proper VAD like WebRTC/Silero) so natural pauses don't cut speech; (b) continuous/rolling noise
estimation instead of a one-shot 250 ms window, decoupled from the SCO warm-up; (c) a real audible
"listening" cue that actually reaches the glasses (fix/replace the earcon, or a short spoken "go");
(d) start recording only once SCO is confirmed hot (kill onset clipping); (e) treat near-silent/low-
energy or low-confidence STT as "didn't catch that" to stop silence-hallucinations reaching the LLM.
Re-run this exact 12-phrase protocol to verify each change moves the numbers.

---

## Capture session 2 — 2026-06-13 (fixes applied + verified on device)

All fixes from the "fix direction" above were implemented (`BtAudio.recordUntilSilence` +
`HomeViewModel.doTalk`, commit `5fb3986`) and tested live on the Pixel (prodDebug/cloud, glasses worn):
- trailing silence 700 ms → **1500 ms**, noSpeechTimeout 4 s → 7 s, maxMs 12 s → 15 s
- robust noise floor (low-percentile over 350 ms + rolling update) instead of the one-shot 250 ms
- speech-start debounce (4 voiced frames); SCO-hot warm-up flush (drop ramp frames)
- audible **listening cue over the SCO route** (`cueListening`) so it reaches the ear
- silence guard in `doTalk` (peak<700 ‖ rms<60 → "didn't catch that", never sent to the LLM);
  spoken "Sorry, I didn't catch that" on a miss

**Result — the dominant failure mode is fixed.** Hard before/after on the same stress phrases:

| phrase | session 1 (before) | session 2 (after) |
|---|---|---|
| "One two three four five" (pauses) | split → "one" / "two three four" | **"One two three four five six seven eight nine ten"** — full, with pauses |
| long sentence *with deliberate stops* | n/a | **captured whole, transcribed perfectly** (14 s, every pause survived) |
| "What time is it?" | onset artifacts / VAD misses | **"What time is it?"** clean ×2 |
| silence (no speech) | STT hallucinated → answered | guard catches it (peak 239) → "didn't catch that" |

Premature endpointing eliminated; noise floor now calibrates correctly (81–104, threshold floored at
500) so speech is reliably detected; no silence-hallucinations reach the LLM.

**Open follow-up:** the in-ear cue was **too faint / sometimes inaudible** (director). The SCO call
channel is quiet on these glasses. Fix in progress: louder near-full-scale **two rising beeps**
(880→1320 Hz, `MODE_STATIC` one-shot, played after the link is hot) — pending device re-verification.
**Possible further work if still faint:** boost in-call (`STREAM_VOICE_CALL`) volume during the cue,
or repeat the beep. Also consider raising maxMs (one long turn hit the 15 s cap, though it still
captured fully).
