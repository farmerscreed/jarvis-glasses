# JARVIS / ECHO — Project Status (single source of truth)

*AI companion for AIMB-G2 smart glasses. Last updated: 2026-06-11.*
*New session? Read this top to bottom. Deeper detail: `docs/recon/00_HANDOFF_START_HERE.md`, `docs/recon/Device_Recon_Record.md`, and the per-area READMEs (`README.md`, `android/README.md`, `supabase/functions/README.md`).*

---

## 1. What this is
A personal AI companion that lives in your ear and sees through your eyes, built on the cheap **AIMB-G2** smart glasses (Jieli JL7018F controller + Allwinner V821 camera; no display — voice-first). The glasses are sensors + a speaker; all intelligence runs on an Android phone + cloud. The keystone is a **shared Personal Memory Index** (Supabase pgvector) every feature reads/writes.

## 2. The journey so far (two phases)
**A. Reverse-engineering the glasses (done — see `docs/recon/`).** From a real unit we established, on hardware:
- **Audio** = standard Bluetooth headset (HFP mic + A2DP speaker).
- **Camera capture** = BLE write `BC 41 03 00 10 50 02 01 01` to characteristic **`de5bf72a`** (service `de5bf728`, ATT handle `0x008E`) on the glasses.
- **Image transfer** = the glasses run an **HTTP server on port 80** reached over **Wi-Fi Direct**; app GETs `http://<ip>/files/media.config` then `/files/<file>`.
- Stock app `com.aitowe.aitoglasses` decompiled; it uses the `com.oudmon.ble` SDK for BLE, **Azure Speech**, and on-device TFLite NLU.

**B. Building our app (in progress — this repo).** A working vertical slice runs on real hardware.

## 3. CURRENT STATUS — what works, verified on a Pixel 8 + glasses

| Capability | Status | Notes |
|---|---|---|
| **Local backend** (Supabase on Docker) | ✅ | `memories` table, pgvector, RLS, `match_memories` RPC. Lint+advisors clean. |
| **Edge Functions** | ✅ | `ingest`, `recall`, `chat` (RAG), `transcribe` (STT). |
| **Memory loop in the app** | ✅ | Sign in → store → recall by meaning → Claude answers grounded in memory. |
| **Audio loop** | ✅ | Glasses mic (SCO) record → playback (A2DP). Peak 13982/32767 captured. |
| **Camera trigger over BLE** | ✅ | App wrote capture cmd ×3 → stock album showed "3 media to import". |
| **Voice assistant loop** | ✅ | "Talk" → glasses mic → Gemini STT → Claude RAG → Android TTS in your ear. Verified: "Where did I park?" → correct spoken answer. |
| **Wake word ("Jarvis")** | ✅ | On-device **Vosk** (offline, no key), phone mic. "Hands-free" toggle. |
| **Glasses physical button trigger** | 🟡 IN PROGRESS | See §6. |

**AI providers wired:** embeddings = Google **Gemini** `gemini-embedding-001` (free, 1536-dim); chat/vision = **Claude** `claude-sonnet-4-6`; STT = Gemini `gemini-2.5-flash` (multimodal); TTS = Android on-device; wake word = Vosk. All keys live server-side in `supabase/functions/.env` (gitignored).

## 4. Architecture
```
GLASSES (mic/speaker + camera)  ── BT audio (HFP/A2DP) + BLE control / Wi-Fi Direct (images)
   │
ANDROID APP (Kotlin/Compose, multi-module)
   wake word (Vosk, phone mic) / Talk button / glasses button(WIP)
   → record glasses mic (SCO) → STT → Claude(RAG over memory) → TTS (A2DP)
   │
SUPABASE (local on Docker): Postgres+pgvector · Auth · Edge Functions (ingest/recall/chat/transcribe)
```
Android modules: `:app` (UI, Hilt DI, ViewModel), `:core` (models), `:ai` (provider interfaces), `:memory` (EchoBackend → Edge Functions), `:assistant` (orchestrator stubs), `:device:ble` (GlassesBleManager + protocol constants), `:device:audio` (BtAudioEngine, TtsEngine, WakeWordEngine, WavUtil), `:device:wifi` (media-transfer stubs).

## 5. How to run (local dev)
1. **Backend:** `supabase start --workdir <repo>` (ports shifted +100 → API 54421, DB 54422, Studio 54423, to coexist with another local project).
2. **Functions:** put keys in `supabase/functions/.env` (see `.env.example`), then `supabase functions serve --env-file supabase/functions/.env --workdir <repo>`.
3. **Device bridge:** `adb reverse tcp:54421 tcp:54421` (phone reaches PC backend at 127.0.0.1).
4. **App:** `android\gradlew.bat -p android :app:installDebug` (or open `android/` in Android Studio). Sign in (dev) uses a test account; `DevConfig` points at `127.0.0.1:54421`.
- Full schema/endpoint/build detail in the per-area READMEs.

## 6. IN PROGRESS — glasses physical-button trigger (where we paused)
Goal: use glasses temple gestures to drive the app (no phone, no always-on mic). **Native gesture map + our repurposing plan are documented in `docs/recon/Glasses_Controls.md`** (per the manual).
- **Two transports.** Trackpad/music → **AVRCP media keys** (volume system-handled). Front/back buttons → **on-device firmware capture + a BLE notification** (NOT AVRCP — our MediaSession saw nothing).
- **CORRECTED model (director, 2026-06-11):** the buttons capture photo/video/audio **in firmware, app-independent** (stored on the glasses, synced later). So we **don't "take over" buttons** — we **LISTEN to the notification and ENRICH** (sync the file + apply AI/memory). **Keep all native captures; do NOT erode video (planned V2 feature).** Pure voice trigger = the wake word (built). See `docs/recon/Glasses_Controls.md` §3 for the per-gesture plan.
- **Built (idle for now):** `app/GlassesButtonController.kt` (MediaSession, only relevant to the AVRCP/music side; keep inactive so music/volume stay native).
- **NEXT STEP (resume here):** extend `GlassesBleManager` to **subscribe to notifications on the notify characteristics** (`0000ae02`/`0000ae04`, `de5bf729`, `6e400003`, `0000fee3`); press each front/back gesture; capture the notification bytes per gesture (log to `EchoBle`); then on each, auto-sync the capture (Wi-Fi Direct → HTTP `/files/`) + apply AI (photo→remember, AI-frame→Look&Ask, audio→transcribe). Also pending: live-confirm firmware-autonomous capture (press front button with no app open → photo still appears).

## 7. Known issues / honest caveats
- **16 KB page warning:** Vosk's `libvosk.so`/`libjnidispatch.so` aren't 16 KB-aligned → Android 16 shows a dismissable "App Compatibility" warning on launch. Device runs 4 KB pages so libs load fine; would matter for a 16 KB device / Play release.
- **Latency:** voice loop records a fixed 5 s then processes (not streaming) → ~10–15 s round trip.
- **Wake word = phone mic** (always-on BT-SCO mic would drain battery + block A2DP). Question after wake uses the glasses mic.
- **Vision half not in-app yet:** we can trigger the camera, but pulling the photo (Wi-Fi Direct → HTTP `/files/`) into the app and feeding Claude vision is not built (Phase 2).
- **Local dev only:** hardcoded test login, local Supabase, cleartext to 127.0.0.1, provider quotas hit and routed around (OpenAI no credit → Gemini; Gemini 2.0-flash STT quota 0 → 2.5-flash).

## 8. Next steps (priority order)
1. Finish the glasses-button trigger (§6).
2. **Vision pipeline (Phase 2):** capture → Wi-Fi Direct → HTTP import → Claude vision ("what am I looking at?").
3. Streaming STT/TTS for low latency; real auth UI; cloud Supabase migration (`supabase db push`).
4. Polish: wake-word on glasses-button instead of phone; 16 KB-aligned native libs; error handling.

## 9. Environment (this machine)
Windows 11. Android SDK at `%LOCALAPPDATA%\Android\Sdk` (platforms 36), JDK 17 (Adoptium), Docker Desktop, Supabase CLI, Node 24, adb. Test device: Pixel 8 (Android 16). Full recon tooling (decompiled stock app, APKs, evidence) lives locally in `..\Jarvis Glasses\` (not committed — too large).
