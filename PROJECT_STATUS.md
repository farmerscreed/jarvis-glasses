# JARVIS / ECHO — Project Status

> **🟢 NEW SESSION? READ `docs/SESSION_HANDOFF.md` FIRST** — it's the holistic session-to-session
> handover (current state, how to run/verify, gotchas, settled decisions, and exactly what's next).
> This file is the detailed feature-status companion to it.

*AI companion for AIMB-G2 smart glasses. Last updated: 2026-06-12.*
*Deeper detail: `docs/PRODUCTION_ROADMAP.md` (full plan + UI/UX), `docs/recon/*` (decoded device protocol).*

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
| **Phase 2: media sync (BLE→Wi-Fi Direct→HTTP)** | ✅ | "Sync from glasses" pulled all 10 captures (9 jpg + 1 mp4) into the app. |
| **Phase 2: Look & Ask (Claude vision)** | ✅ | Describes the latest synced photo, speaks it, stores a memory. Verified live. |
| **Phase B: glasses-button reactions** | ✅ (in-app) | Button-event protocol decoded (`docs/recon/Glasses_Controls.md` §4). Press → BLE event → auto-sync → route: photo→caption+memory, audio→transcribe+voice-note, video→upload (V2), AI-gesture→Look&Ask. Verified on device 2026-06-12 (capture→"Done — 1 new capture(s) processed", no loops). Foreground service (works with app killed) still pending. |

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
4. **App:** `android\gradlew.bat -p android :app:installDevDebug` (or open `android/` in Android Studio). Sign in (dev) uses a test account; the dev flavor's BuildConfig points at `127.0.0.1:54421` (`:app:installProdDebug` targets cloud `jarvis-prod`).
- Full schema/endpoint/build detail in the per-area READMEs.

## 6. Recently completed + what's deferred
**Phase 2 (vision) — DONE & verified on device (2026-06-11):**
- **Transfer protocol fully decoded** (`docs/recon/Transfer_Protocol.md`): oudmon BLE frame `BC 41 <len:2LE> <CRC16-MODBUS:2LE> <payload>` → char `de5bf72a`; Wi-Fi-start payload `02 01 04`; glasses' IP arrives on notify `de5bf729` (type `0x08`); then Wi-Fi Direct + `GET http://<ip>/files/media.config` → each file. CRC reversed from the known camera-trigger bytes.
- **Implemented & working:** `:device:ble` `GlassesBleManager` (CRC framer, `startWifiTransfer()`, IP-notify parse), `:device:wifi` `GlassesP2pManager` (Wi-Fi Direct) + `MediaTransferClient` (HTTP pull), `vision` Edge Function (Claude multimodal), VM `syncGlasses()` + `lookAndAsk()`, UI "Sync from glasses" / "Look & Ask". **Verified:** synced 10 items; Look & Ask described a synced photo aloud + stored a memory.

**Glasses physical-button trigger — DEFERRED (design decided, not built):** Per `docs/recon/Glasses_Controls.md`: buttons capture photo/video/audio **in firmware, app-independent** (verified: 10 items captured with no app running). Keep all native captures (esp. video for V2); the app should **listen to each button's BLE notification and react** (auto-sync + AI). To build later: subscribe to notify chars, press each front/back gesture, record the notification bytes (log `EchoBle`), then route each press to a feature. `GlassesButtonController` (MediaSession) exists but stays idle (music/volume stay native).

## 7. Known issues / honest caveats
- **16 KB page warning:** Vosk's `libvosk.so`/`libjnidispatch.so` aren't 16 KB-aligned → Android 16 shows a dismissable "App Compatibility" warning on launch. Device runs 4 KB pages so libs load fine; would matter for a 16 KB device / Play release.
- **Latency:** voice loop records a fixed 5 s then processes (not streaming) → ~10–15 s round trip.
- **Wake word = phone mic** (always-on BT-SCO mic would drain battery + block A2DP). Question after wake uses the glasses mic.
- **Vision half not in-app yet:** we can trigger the camera, but pulling the photo (Wi-Fi Direct → HTTP `/files/`) into the app and feeding Claude vision is not built (Phase 2).
- **Dev flavor only:** hardcoded test login + cleartext to 127.0.0.1 (the prod flavor compiles both out and targets cloud over TLS); provider quotas hit and routed around (OpenAI no credit → Gemini; Gemini 2.0-flash STT quota 0 → 2.5-flash).

## 8. Next steps (priority order)
*Full start-to-finish production plan (incl. offline-first architecture + UI/UX design workflow): `docs/PRODUCTION_ROADMAP.md`.*
1. ~~**Glasses-button reactions**~~ **DONE in-app (2026-06-12):** protocol decoded + reactions live (see §3 row). Remaining for Phase B: (a) live-press verification of the AI gesture + audio route by the director (BLE-cmd capture path verified end-to-end), (b) `ConnectedCompanionService` foreground service so reactions work with the app backgrounded/killed. **Gotchas discovered (documented in `Glasses_Controls.md` §4):** glasses delete their files + emit a zero-inventory event after each sync (only an inventory *increase* = new capture); `BC 41` frames are command-ACK echoes, never events (parsing them as events causes a capture storm); audio records as `.opus` (route as `audio/ogg`); Wi-Fi-start ACK leaks SSID+passphrase.
2. ~~**Persist images**~~ **DONE (2026-06-11, server-verified):** private `media`/`audio` buckets + owner RLS (migration); `EchoBackend.uploadMedia()`/`signedMediaUrl()`; `media_path` now actually sent by `IngestRequest` (was silently dropped); Look&Ask uploads the photo and stores its storage key. **Verified on device (2026-06-12):** glasses capture → sync → Look & Ask → memory row carries the storage key → signed URL serves the real JPEG (695 KB) → anon read blocked. Gallery/timeline UI deferred to the design-integration phase (`docs/PRODUCTION_ROADMAP.md` §11).
3. ~~**Phase C — offline-first rebuild**~~ **DONE & device-verified (2026-06-12)** (`docs/PRODUCTION_ROADMAP.md` §4):
   - **C1** local-first memory core — capture writes Room first, drains an outbox; `client_id` idempotency; `ConnectivityGovernor` + "Cloud: …" chip.
   - **C2** background drain (WorkManager, survives app kill) + persisted session token. Verified: off-grid save → process killed → Wi-Fi on → a fresh WorkManager-woken process synced with no UI.
   - **C3** on-device embeddings (bundled USE via MediaPipe) → **offline semantic recall**. Verified: "where did I leave my vehicle" (no shared words) returned "I parked on level 3…".
   - **C4** Jarvis Lite (rule-based off-grid answer floor) — off-grid `ask()` phrases & speaks a real answer; voice→text modality fallback when off-grid.
   - **C5** deferred vision/transcribe re-run — off-grid captures save a placeholder (held back from sync), then get real Claude/Gemini content on reconnect. Verified: off-grid Look & Ask → placeholder → reconnect → real description synced.
   - **C6** LEAN tier — RTT probe (FULL/LEAN/OFF_GRID), "online · slow" chip, `ask()` fails fast to on-device answer on a slow link.
   **Deferred by design:** the optional large on-device LLM ("Offline Pack", ~1–2 GB) — the rule-based floor makes the product offline-complete without it; the pack is a download-gated quality enhancement. **Small follow-ups (not blockers):** refresh-token rotation for long-lived background auth; on-device dictation (Vosk) for true off-grid voice. Phase C is also the cost lever (§4.7).
4. **Phase D — latency war (in progress).** **D1 DONE & on-device:** VAD endpointing (`recordUntilSilence` — stop ~0.7 s after you stop talking vs fixed 5 s), earcons, per-stage `EchoLatency` instrumentation. Measured baseline (no-speech): record≈6.6 s · stt≈3.7 s · llm≈5.1 s. **D2 streaming chat — now wired into the live loop (2026-06-12):** `ask()` + the voice path stream Claude's answer sentence-by-sentence (`chatStream` → `SentenceChunker` → `TtsEngine.enqueue`/`finishStream`) on a FULL-tier link, falling back to one-shot chat on LEAN/failure. Unverified until the cloud functions deploy (local Kong buffers SSE).
5. **Phase E — real users (STARTED 2026-06-12):** cloud Supabase project **`jarvis-prod`** (ref `agtuimnppqbrjocuzqsk`, West EU) created + linked; **dev/prod build flavors** (prod = TLS-only cloud, dev login compiled out); **email-OTP sign-in** + **refresh-token rotation** (401 → rotate → retry on all authed paths — closes the long-lived background-auth gap from Phase C). **Pending:** director-run `db push`/`secrets set`/`functions deploy` (agent-gated), Magic Link email template needs `{{ .Token }}`, then on-device verification; onboarding wizard lands with the design integration (35-screen Stitch design system committed to `docs/design/`).
6. Polish: 16 KB-aligned native libs (Vosk).

**Settled decisions (2026-06-12, see `docs/PRODUCTION_ROADMAP.md`):**
- **Two-Speed Brain** (§1): fast reflexive lane (voice, Phases C/D) + deliberate lane (capture-anchored, tool-using, patient — V2).
- **Cost & auth** (§4.7): API-only (the Claude subscription cannot back the app — rejected); cost controlled by tiered models + prompt caching + Batch API + offline-first. Est. **$3–10/mo** core, **~$15–35/mo** power use.
- **"Ask Jarvis" deliberate lane** (§10a): price-check / research / count-materials from a photo. **GATE: brainstorm the screenless multi-turn-with-a-photo UX puzzle BEFORE building it.**

## 9. Environment (this machine)
Windows 11. Android SDK at `%LOCALAPPDATA%\Android\Sdk` (platforms 36), JDK 17 (Adoptium), Docker Desktop, Supabase CLI, Node 24, adb. Test device: Pixel 8 (Android 16). Full recon tooling (decompiled stock app, APKs, evidence) lives locally in `..\Jarvis Glasses\` (not committed — too large).

## 10. The product plan (5 core features, one shared Memory Index)
Full detail in `docs/recon/01_IMPLEMENTATION_PLAN.md` + `…Project_Brief.md`. Summary of plan vs. status:
1. **Visual Second-Brain** (keystone) — capture (photo+note+time) → memory; recall by NL. → memory loop ✅, photo capture+sync+vision ✅; *next: auto-capture-to-memory on button press*.
2. **Look & Ask** — frame → Claude vision in your ear. → **✅ working** on synced photos (glasses-button trigger pending).
3. **Read-it-to-me (OCR)** — point at text → hear it. → not built (same pipeline as Look&Ask + OCR prompt).
4. **Meeting Capture** — record audio → transcribe → summarize → recall. → STT exists + glasses record audio in firmware; *not wired as a feature yet*.
5. **Translation** — sign/menu (OCR→translate→speak) + live conversation. → not built (Claude can translate; needs OCR/audio paths).

**Phasing:** Phase 0 (foundation) ✅ · Phase 1 (audio loop / voice assistant) ✅ · **Phase 2 (vision: capture→sync→Claude vision→memory) ✅** · Phase 3 (polish: button-trigger, streaming low-latency, translation/OCR, cloud Supabase, productionize) — partially started. See §8 for the prioritized next steps.
