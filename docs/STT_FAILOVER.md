# Speech-to-text (STT) options — on-device first, cloud as fallback

*Opened 2026-06-13 after the Gemini free-tier daily quota (≈20 req/day on `gemini-2.5-flash-lite`,
both STT models 429 `RESOURCE_EXHAUSTED`) took voice transcription down mid-session.*

**Director steer (2026-06-13): prefer ON-DEVICE STT** — transcribe on the phone instead of the cloud,
for real-time response that doesn't depend on a reliable network and can never hit a quota wall. This
also fits JARVIS's offline-first design (it already bundles Vosk for the wake word) and is *faster*:
our cloud STT was taking **7–13 s** per turn (network + retries), whereas on-device **batch**
transcription of one VAD-endpointed utterance runs **~1–3 s**. So the plan inverts: **on-device is the
primary/default; cloud becomes an optional online quality-upgrade + fallback.**

Our pipeline already captures a *complete* utterance (record → VAD endpoint → WAV), i.e. **batch**
transcription — which is exactly where on-device engines are fast (streaming on-device is the slow
path; we don't need it).

---

## On-device STT options (Android, Pixel 8 / Tensor G3)

| Engine | Model(s) | Size | Quality | Notes |
|---|---|---|---|---|
| **sherpa-onnx** (k2-fsa) ⭐ | Whisper, **Moonshine**, SenseVoice, Qwen3-ASR, Parakeet | ~40–250 MB | High (Whisper/Moonshine) | **Best fit.** Offline, Android lib + prebuilt APKs, transcribes our PCM buffer (keeps our VAD/cue pipeline), device-independent. Moonshine = fast on-device English; Whisper-base.en = noise-robust. |
| **whisper.cpp** | Whisper tiny/base/small (ggml, quantized) | tiny ~40 MB, base ~80 MB | High | **Batch is fast** (~5 s audio in 1–2 s; tiny short clip ~3 s) — perfect for our endpointed clips. Streaming is slow (avoid). TFLite Android wrappers exist. |
| **Android on-device `SpeechRecognizer`** | Google's on-device model (`createOnDeviceSpeechRecognizer`, API 31/33+) | 0 (built-in) | **Excellent on Pixel** | Zero bundling, free, offline. Catch: it records the *live mic* (does its own VAD/endpointing — conflicts with ours) and quality varies by OEM (great on Pixel, unknown elsewhere). Fast UX-prototype path. |
| **ML Kit GenAI Speech Recognition** | Gemini Nano via AICore | 0 (system) | High | New (2026 alpha), on-device, Pixel/AICore-gated. Watch, not yet a dependency. |
| **Vosk** (already integrated) | English small/large | 50 MB / 1.8 GB | Lower (esp. noisy/accented) | Already bundled for the wake word. CPU-only, native streaming. Weakest accuracy on the noisy glasses mic — fine as a last-ditch off-grid floor, not the primary. |

### Recommendation (on-device)
- **Primary: sherpa-onnx with Whisper-base.en (noise-robust) or Moonshine-base (fastest).** Embed it,
  feed it the PCM we already capture, return text in ~1–3 s — fully offline, no quota, device-independent.
  Keeps the whole record→VAD→cue pipeline we just tuned; only `backend.transcribe()` is swapped for a
  local call (or made the first leg of the chain).
- **Fast prototype option:** wire Android `createOnDeviceSpeechRecognizer` first to validate the
  on-device UX on the Pixel in an afternoon, then move to sherpa-onnx for control + portability.
- Model ships either bundled (~80–150 MB APK bump) or **downloaded on first run** (keeps the base APK
  small; matches the "Offline Pack" download-gated pattern already in the roadmap).

### Trade-offs to weigh
- On-device base/tiny Whisper is a touch less accurate than cloud Whisper-large on *hard* audio
  (heavy noise/accents) — hence keeping cloud as an optional upgrade when online.
- APK size / first-run model download.
- Battery/CPU per turn (a ~1–3 s inference on Tensor G3 is negligible for occasional turns).

---

## Cloud STT (now the FALLBACK / online quality-upgrade, not the default)

Kept for when online and we want max accuracy, or if on-device is deferred. A multi-provider failover
chain so no single quota wall takes it down — free legs first, cheap paid backstops after.

## The problem with today's setup
`supabase/functions/transcribe` calls **Gemini 2.5-flash → 2.5-flash-lite** only. The free tier daily
cap is tiny (~20/day) and conversation mode multiplies STT calls (every turn + reprompts), so it
exhausts fast. When it fails the app showed a silent blank ("didn't catch that"). Gemini is also one
of the **most expensive per-minute** options for plain STT (~$0.037/min), so it's a poor paid leg too.

## Options compared (current, June 2026)

| Provider / model | Paid $/min | Free tier | Why it matters here |
|---|---|---|---|
| **Groq — Whisper Large v3 Turbo** | **$0.0006** | **2,000 req/day free** | Cheapest + best free tier; ~228× realtime (fast). Ideal **primary free leg**. |
| **Alibaba — Qwen3-ASR-Flash** (Chinese) | **$0.0021** ($0.000035/s) | trial credits | **Built for noisy/far-field + context biasing** (pass names/jargon) — a strong fit for a glasses mic. 11 langs incl. EN (US/UK). DashScope / OpenAI-compatible API / OpenRouter. |
| **OpenAI — gpt-4o-mini-transcribe** | $0.003 | — | Strong accuracy, cheap, dead-simple API. Good **paid backstop**. |
| **ElevenLabs — Scribe** | $0.004 | limited | High accuracy, diarization included. |
| **Deepgram — Nova-3** | $0.0048–0.0077 | **$200 credit (~433 h), no expiry** | Fast, accurate; the huge one-time credit lasts ages for personal use. |
| **OpenAI — gpt-4o-transcribe / whisper-1** | $0.006 | — | 4.1% WER (vs Whisper-v3 5.3%). |
| **AssemblyAI — Universal** | ~$0.0025 | $50 credit (~185 h) one-time | Solid; credit is one-time not recurring. |
| **Google — Gemini 2.5-flash** (current) | ~$0.037 | ~20/day (tiny) | Expensive per-min; the small free cap is exactly what bit us. |

(Self-host option: **Whisper.cpp / faster-whisper / SenseVoice (FunASR)** run on-device/own-server for
~free, but add infra + latency; deferred — keep as a future off-grid STT, ties into the Vosk dictation
TODO.)

## Recommended failover chain (free → paid backstop)
Try in order; on `429` / quota / 5xx / empty, fall through to the next:

1. **Groq Whisper Large v3 Turbo** — *free, 2,000/day*. Generous enough that personal daily use likely
   never leaves this leg. Fast.
2. **Gemini 2.5-flash** — *free, existing*. Secondary free leg (covers Groq per-minute bursts / outages;
   recovers daily).
3. **Qwen3-ASR-Flash** *(paid, $0.0021/min)* **or** **OpenAI gpt-4o-mini-transcribe** *(paid, $0.003/min)*
   — guaranteed paid backstop so voice **never** fully dies. Qwen edges it on noise-robustness for the
   glasses mic + context biasing; OpenAI edges it on integration simplicity.

**Cost reality for personal use:** a few hundred 5–10 s turns/day sit inside Groq's free 2,000/day = **$0**.
If a day ever overflows to the paid backstop, it's pennies (Qwen $0.0021/min ⇒ ~$0.0002 per 6 s turn).

## Implementation sketch (when approved)
- Extend `transcribe/index.ts` from a model chain to a **provider chain**: ordered list of
  `{name, call(audioBase64, mime) -> text}`; iterate, fall through on error/empty; return `{text, provider}`.
- Each provider key as a Supabase **function secret** (`GROQ_API_KEY`, `OPENAI_API_KEY`,
  `DASHSCOPE_API_KEY`, keep `GEMINI_API_KEY`). Director must create the accounts/keys.
- Groq & OpenAI expose **OpenAI-compatible `/audio/transcriptions`** (multipart) — send the WAV; Qwen via
  DashScope (OpenAI-compatible) too. Minimal per-provider glue.
- Keep the app-side error surfacing already added (so a *total* chain failure still tells the user).
- Log which provider served each turn to watch cost/quality.

## Decisions for the director
1. Which providers to wire (recommend **Groq free + Gemini free + one paid backstop**).
2. Paid backstop: **Qwen3-ASR-Flash** (noise-robust, Chinese, cheapest paid that's English-good) vs
   **OpenAI gpt-4o-mini** (simplest). Could include both (Qwen then OpenAI).
3. Provide the API keys for the chosen providers; then it's a contained change to one Edge Function.

## Sources
- Groq pricing/free tier: tokenmix.ai/blog/groq-api-pricing, eesel.ai/blog/groq-pricing
- OpenAI transcribe pricing: tokenmix.ai/blog/gpt-4o-transcribe-speech-to-text-api-guide-2026, cloudzero.com/blog/openai-pricing
- Qwen3-ASR-Flash: openrouter.ai/qwen/qwen3-asr-flash, alibabacloud.com/help/en/model-studio/qwen-asr-api-reference, slator.com (Alibaba ASR)
- Deepgram / AssemblyAI: deepgram.com/learn/best-speech-to-text-apis-2026, assemblyai.com/pricing
- Gemini pricing: ai.google.dev/gemini-api/docs/pricing
