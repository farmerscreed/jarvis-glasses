# JARVIS — Session Handoff (read this FIRST)

*The single-source handover between sessions. A new session should be able to read only this and
know exactly where we are, how to run/verify, what not to relitigate, and what to do next.*
*Last updated: 2026-06-12. Keep this current at the end of every working session.*

**Repo is PUBLIC since 2026-06-12:** https://github.com/farmerscreed/jarvis-glasses (director's
call). History was secret-swept before the flip — real keys live only in gitignored `.env` files;
the committed `sb_*` keys are the CLI's shared local-dev defaults + the cloud anon key (public by
design). Never commit a real key; remember `git push` now publishes immediately.

**Branding (2026-06-12):** `/branding` — presence-orb logomark + JARVIS lockup SVGs (dark/light,
Space Grotesk embedded), `play_store_icon_512.png`. Adaptive launcher icon wired in the manifest
(`ic_launcher` vector foreground + `#0B0F14` background + monochrome layer).

**Deeper docs (this file points to them; don't duplicate their depth here):**
`PROJECT_STATUS.md` (feature status) · `docs/PRODUCTION_ROADMAP.md` (the full plan A→H + UI/UX
design prompt) · `docs/recon/Glasses_Controls.md` & `docs/recon/Transfer_Protocol.md` (decoded
device protocol) · `docs/recon/Device_Recon_Record.md` (hardware facts).

---

## 1. What this is (10-second orientation)

A voice-first AI **companion for AIMB-G2 smart glasses** (camera + mic + speaker, **no display**).
The glasses are sensors + a speaker; all intelligence runs on an **Android phone (Kotlin/Compose,
multi-module)** + **Supabase** backend. The keystone is a **Personal Memory Index** (Postgres +
pgvector) every feature reads/writes. Built offline-first: it must work in a tunnel / on a plane.

---

## 2. Current state — what's DONE & VERIFIED on device

| Phase | Status | One line |
|---|---|---|
| **0** foundation | ✅ | Supabase local, `memories` pgvector + RLS, 8-module app, Edge Functions, RAG verified. |
| **1** voice loop | ✅ | Glasses mic → STT → Claude RAG → TTS in your ear; Vosk wake word "Jarvis". |
| **2** vision | ✅ | BLE camera trigger → Wi-Fi Direct sync → Claude vision "Look & Ask" → memory. |
| **A** image persistence | ✅ | Private `media`/`audio` buckets + RLS; photos upload; `media_path` threaded (fixed a silent drop). |
| **B** glasses-button reactions | ✅ (in-app) | Button event protocol decoded; press → auto-sync → route (photo→caption, audio→transcribe, AI-gesture→Look&Ask). |
| **C** offline-first | ✅ **complete** | Local-first core + outbox + idempotency, background drain (survives app kill), on-device embeddings (offline semantic recall), Jarvis Lite floor, deferred AI re-run, LEAN tier. |
| **D** latency | 🟡 **partial** | D1 done (VAD endpointing, earcons, instrumentation). **D2 streaming VERIFIED on device vs cloud (2026-06-12):** text Ask spoke the answer streamed sentence-by-sentence. Voice-path `EchoLatency` numbers still to capture (needs a glasses voice turn). |
| **E** real users | 🟢 **core done (2026-06-12)** | Cloud `jarvis-prod` fully deployed (migrations/secrets/6 functions); dev/prod flavors; **director signed in on device via emailed 6-digit code (Resend)** and got a streamed spoken answer. Remaining: Google One-Tap, function rate limits, foreground service. |
| **UI** design integration | 🟢 **core done (2026-06-12)** | Steps 1–5 of the director's UI plan: tokens → JarvisTheme (M3, dark-only, tri-font, amber via CompositionLocal) → shared components → animated PresenceOrb (6 states) → designed screens (Live console ×4 variants, Timeline, Gallery empty-state, Settings) wired to HomeViewModel **with zero engine changes**. Live + Settings verified on device vs the design PNGs. Dev console preserved under Settings → Developer console. |

**Phase C detail (all six increments verified 2026-06-12):**
- **C1** every capture writes Room first, drains an outbox to cloud; `client_id` idempotency (server dedupes). `MemoryStore`, `ConnectivityGovernor`, "Cloud: …" chip.
- **C2** WorkManager background drain (survives app kill) + persisted session token. `SyncWorker`/`SyncScheduler`.
- **C3** bundled Universal Sentence Encoder (MediaPipe) → offline semantic recall. `Embedder`/`MediaPipeEmbedder`, `LocalMemory.embedding`. Dual-embedding: local off-grid-only, Gemini 1536-dim canonical online.
- **C4** `JarvisLite` rule-based floor — off-grid `ask()` phrases + speaks a real answer; voice→text modality fallback off-grid.
- **C5** `reprocessDeferred()` — off-grid captures save a placeholder (held back from sync), get real Claude/Gemini content on reconnect.
- **C6** RTT probe → FULL/LEAN/OFF_GRID; "online · slow" chip; `ask()` fails fast to on-device answer on a slow link.

**D1 measured baseline (on device, `EchoLatency` logcat):** record≈6.6 s (1.5 s SCO setup + VAD)
· stt≈3.7 s · llm≈5.1 s · time-to-speak≈15.5 s (no-speech run). This directs the rest of Phase D.

---

## 3. Architecture & where things live

**Android modules** (`android/`, package root `com.echo`):
- `:app` UI layer (since the UI pass, 2026-06-12): `ui/theme/` (JarvisTheme — Color/Type/Shape/
  Spacing, "Aetheric Intelligence" tokens from `docs/design/`; amber + ember + glow via
  `JarvisTheme.colors`, transcripts via `JarvisTheme.dataMono`), `ui/components/` (chips, buttons,
  GlowFab, transcript bubble, MemoryCard, tool-result cards, search field, top/bottom bars,
  `PresenceOrb.kt` — 6 animated states), `ui/screens/` (LiveConsole/Timeline/Gallery/Settings),
  `ui/dev/DevConsoleScreen.kt` (the old console, behind Settings → Developer console),
  `ui/AppRoot.kt` (tab shell). Fonts: variable TTFs in `res/font/` (Space Grotesk/Inter/JetBrains
  Mono). **The UI pass changed no engine/ViewModel code.**
- `:app` — UI (Compose), `HomeViewModel.kt` (the orchestrator),
  Hilt DI (`di/AppModule.kt`), `JarvisApp.kt`, `JarvisLite.kt`, `SentenceChunker.kt`,
  `GlassesButtonController.kt`, `sync/SyncWorker.kt` + `SyncScheduler.kt`, `ml/MediaPipeEmbedder.kt`.
- `:core` — `model/Memory.kt`, `MemoryType`.
- `:memory` — backend + offline-first core: `EchoBackend.kt` (OkHttp to Supabase),
  `SupabaseSession.kt` (persisted token), `MemoryStore.kt` (local-first remember/recall/drain/
  reprocess), `ConnectivityGovernor.kt`, `Embedder.kt`+`VectorUtil`, `MemoryStoreFactory.kt`,
  `Dto.kt`, `local/` (Room: `LocalMemory`, `MemoryDao`, `MemoryDatabase`).
- `:ai` — provider interfaces. `:assistant` — orchestrator stubs.
- `:device:ble` — `GlassesBleManager.kt` (CRC framer, notifications), `GlassesEvent.kt` (decoded button events).
- `:device:audio` — `BtAudio.kt` (`BtAudioEngine`: SCO record incl. `recordUntilSilence` VAD + `earcon`), `Tts.kt`, `WakeWordEngine.kt` (Vosk), `WavUtil.kt`.
- `:device:wifi` — `GlassesP2pManager.kt` (Wi-Fi Direct), `GlassesMedia.kt` (`MediaTransferClient`).

**Backend** (`supabase/`): migrations `20260610214254_init_memory_schema` (memories + pgvector +
`match_memories` RPC + RLS), `20260611110843_storage_media_buckets` (media/audio buckets + RLS),
`20260612120000_add_client_id_idempotency`. Edge Functions: `ingest`, `recall`, `chat`,
`transcribe`, `vision`, `chat-stream` (SSE, now wired into the live loop), `_shared/`.

**Cloud (Phase E, created 2026-06-12):** Supabase project **`jarvis-prod`**, ref
`agtuimnppqbrjocuzqsk`, West EU (Ireland), org `bfpprerlkqqhoktadliq`. CLI is `supabase link`ed.
DB password + ref live in gitignored `supabase/.env`. Migrations, function secrets, and all 6
functions are **deployed** (2026-06-12). App flavors: **dev** = local stack (cleartext +
"Sign in (dev)"), **prod** = `https://agtuimnppqbrjocuzqsk.supabase.co` (TLS only, OTP sign-in only).

**AI providers** (keys in `supabase/functions/.env`, gitignored): embeddings = Gemini
`gemini-embedding-001` (1536-dim, free); chat/vision = Claude `claude-sonnet-4-6`; STT = Gemini
`gemini-2.5-flash`; TTS = Android on-device; wake word + on-device embeddings = bundled models.

---

## 4. How to run & verify (the dev loop)

Run from repo root `C:\Users\admin\Documents\APP\jarvis` (Windows, PowerShell). `$w` below = that path.

```powershell
# 1. Backend (Docker Desktop must be running)
supabase start --workdir $w                 # API 54421, DB 54422, Studio 54423 (ports +100)
supabase functions serve --env-file supabase\functions\.env --workdir $w   # run in BACKGROUND; serves all functions
# (a new function dir needs a serve restart to be picked up)

# 2. Device bridge (phone reaches PC backend at 127.0.0.1)
adb reverse tcp:54421 tcp:54421             # RE-ASSERT after ANY Wi-Fi toggle (see gotchas)

# 3. App — flavors since Phase E: dev = local stack, prod = cloud jarvis-prod
android\gradlew.bat -p android :app:installDevDebug    # local-backend build (the usual dev loop)
android\gradlew.bat -p android :app:installProdDebug   # cloud build (no adb reverse needed)
```

**Dev login:** `tester@local.dev` / `password123` (the "Sign in (dev)" button). Token now persists,
so the app auto-restores "Signed in" across launches.

**Driving the UI headless** (no human): dump + tap by text via uiautomator —
`adb shell uiautomator dump /sdcard/ui.xml; adb shell cat /sdcard/ui.xml`, regex the node's
`text="…" … bounds="[x1,y1][x2,y2]"`, `adb shell input tap <cx> <cy>`. Compose text fields aren't
`EditText` — find them by their current text; type with `adb shell input text "a%sb"` (`%s`=space)
after `KEYCODE_MOVE_END` + repeated `KEYCODE_DEL` to clear. Scroll with mid-screen swipes (edge
swipes open the notification shade).

**Verifying offline-first:** `adb shell svc wifi disable/enable` flips the governor (OFF_GRID/FULL).
**Re-assert `adb reverse` after every toggle.** Simulate an app-killed background drain with
`adb shell am kill com.echo.companion` (NOT `force-stop` — that cancels WorkManager jobs).
Inspect the DB via `… | docker exec -i supabase_db_jarvis psql -U postgres -d postgres`.

---

## 5. Environment & operational gotchas (these bite every session)

- **Test device:** Pixel 8, Android 16, serial `43230DLJH001YY`, **adb over USB**.
- **Glasses:** BT name `AIMB-G2_A034`, MAC `63:93:E1:8A:A0:34`. They are **single-audio-host**.
- **The PC steals the glasses' Bluetooth.** They were paired to this PC during recon; Windows
  auto-grabs them on power-on and the phone then can't connect. Their PC pairing entries are
  currently **disabled** (reversible: `Enable-PnpDevice` on the `*6393E18AA034*` instances, elevated).
  If the phone won't pair, check the PC isn't holding them.
- **`adb reverse` drops whenever Wi-Fi toggles** (the bridge re-registers). After any
  `svc wifi enable/disable`, run `adb reverse tcp:54421 tcp:54421` again — in a loop during
  reconnect tests. This is a test-harness artifact, not a product bug (real devices use real internet).
- **No `sqlite3` on the device** — can't inspect the Room DB via `run-as … sqlite3`. Verify
  offline-first functionally (UI chip + server rows) instead.
- **Functions server runs as a background task** and does NOT survive across sessions — start it fresh.
  A **new** function directory requires restarting `functions serve`.
- **Provider keys** live only in `supabase/functions/.env` (gitignored). If functions 500 on AI
  calls, the env file / keys are the first suspect. Past quota dodges: OpenAI→Gemini, Gemini
  2.0-flash STT→2.5-flash.
- **CRLF warnings** on `git commit` are normal (Windows line endings) — ignore.
- **PowerShell:** no heredoc; for multi-line commit messages write the message to a temp file and
  `git commit -F <file>` (double-quotes in `-m` break it).
- **Decoded device gotchas** (full detail in `docs/recon/Glasses_Controls.md` §4): glasses **delete
  their files after a successful sync** and emit a zero-inventory event (only an inventory *increase*
  = a new capture); `BC 41` frames on `de5bf729` are **command-ACK echoes, NOT events** (parsing
  them caused a capture storm); audio records as `.opus` (route as `audio/ogg`); the Wi-Fi-start ACK
  leaks the P2P SSID+passphrase in cleartext.

---

## 6. What's NEXT — the critical path

**Two finish lines, be explicit about which you're targeting:**
- **"You can wear it every day" (personal daily-driver):** much closer — foreground service, cloud
  migration, a real login for you, warm-path latency, and a real UI skin.
- **"A stranger installs from Play":** the full A→H list — onboarding, hardening, privacy/legal,
  release engineering, ops. This is the bulk of the remaining effort (careful, not risky).

**Recommended critical path:** Phase **E** (cloud + auth + onboarding) → **UI design + integration**
→ Phase **F** (privacy/hardening) → Phase **G** (Play release) → **H** (ops). Reasoning: E unblocks
finishing the streaming latency work and is the prerequisite for everything user-facing.

**Phase E — Real users (in progress, 2026-06-12):**
- ✅ **Cloud project** `jarvis-prod` created + linked (ref `agtuimnppqbrjocuzqsk`); ✅ `dev`/`prod`
  build flavors; ✅ **email-OTP auth** (request + verify, UI) with dev login compiled out of prod;
  ✅ **refresh-token rotation** (persisted, rotated on any 401 — fixes long-lived background auth);
  ✅ **streaming LLM→TTS wired into the live loop** (ask + voice path, FULL tier, with one-shot
  fallback). All compile; none device-verified against cloud yet.
- ✅ **Cloud deployed (director-authorized, 2026-06-12):** all 3 migrations pushed, provider keys
  set as function secrets, all 6 Edge Functions deployed. Verified: auth health OK; every function
  answers **401 without a user JWT** (verify_jwt on). Prod-flavor build installed on the Pixel.
- ✅ **Auth email: custom SMTP via the director's Resend (domain `leiko.app`)** — no dashboard step
  needed. Pushed with `supabase config push` from `config.toml`: SMTP `smtp.resend.com:465` as
  `Jarvis <jarvis@leiko.app>` (key = `RESEND_API_KEY` in gitignored `supabase/.env`, referenced via
  `env()`), Magic Link template = 6-digit `{{ .Token }}` (`supabase/templates/magic_link.html`),
  OTP length 8→6 (matches the app), email rate limit 2→30/h, site_url → `https://leiko.app`.
  **Verified:** `/auth/v1/otp` for the director's email returned 200 → real code email delivered
  through Resend (2026-06-12).
- **PITR: skipped by decision (no paid features).** Backup story instead: if the org is on Pro,
  daily automated backups are already included; additionally a local logical dump any time:
  `supabase db dump -f backups/jarvis.sql --password <pw>` (+ `--data-only` variant for rows).
  Take one after any schema change.
- **Still to do:** device e2e verify (OTP sign-in → remember/ask → streaming voice turn),
  Google One-Tap (needs an OAuth client), rate limits on functions. **Onboarding wizard + all
  UI/UX integration is parked until after this version** (director decision 2026-06-12); the
  35-screen Stitch design system is committed at `docs/design/` for when that starts.

**Carried-over small, high-leverage items (can do anytime):**
- **`ConnectedCompanionService` foreground service** — so glasses-button reactions + sync work with
  the app backgrounded/killed (Phase B remainder; pairs with the WorkManager work).
- **Live-press verification** of the AI-gesture + audio routes (needs glasses + the director).
- On-device **Vosk dictation** for true off-grid voice (today off-grid voice → "type instead").
- **Deferred-vision/transcribe** re-run is built & verified; nothing owed.

**Then:** Phase **F** (reliability state machines + crash telemetry, battery, security: remove
cleartext, R8, **privacy/legal: recording consent + mic-hot indicator + GDPR export/delete**),
Phase **G** (16 KB Vosk alignment, signing, Play data-safety + permission declarations, beta ladder
incl. a non-Pixel), Phase **H** (per-user budget kill-switch, dashboards, support loop).

**UI/UX:** the app is a **dev console** today. The real "Companion Console" design (16 screens + the
orb) is designed **externally** (Figma via Google Stitch using the prompt in roadmap §11.3), handed
back as **PNG screenshots of every screen+state + a tokens spec (hex/type/spacing/radii) + assets
(SVG icons, font files)**, then integrated as a `JarvisTheme` + Compose screens over the engine
(roadmap §11.4 — nothing in the engine changes for the skin to land).

**V2 (NOT on the v1 production path):** the **"Ask Jarvis" deliberate lane** (price-check / research
/ count-materials from a photo). **GATE:** brainstorm the screenless "conversation-with-a-photo" UX
before building (roadmap §10a).

---

## 7. Settled decisions — do NOT relitigate

- **Backend = Supabase backbone; add FCM (push) from Firebase later.** Postgres+pgvector+RLS+Edge
  Functions+Storage are the right, already-built foundation. **Do not migrate to Firebase** (Firestore
  is a poor fit for the vector memory core; a full move is weeks of regression). Cherry-pick only
  **FCM for push** (needed for notifications/the noticer; easy, additive) and optionally Crashlytics.
- **API-only for the brain; the Claude Pro/Max subscription CANNOT back the app** (separate product,
  ToS/rate-limit/account risk). Control cost via tiered models (Haiku/Sonnet/rare Opus) + prompt
  caching + Batch API + offline-first. Est. $3–10/mo personal, ~$15–35/mo power use (roadmap §4.7).
- **Two-Speed Brain:** fast reflexive lane (voice, Phases C/D) + deliberate lane (capture-anchored,
  tool-using, patient — the V2 "Ask Jarvis"). The orb visualizes both.
- **Offline "Offline Pack" (large on-device LLM) is deferred by design** — the rule-based Jarvis Lite
  floor makes the product offline-complete; the big LLM is an optional download-gated quality upgrade.
- **Streaming chat is wired into the live loop (Phase E, 2026-06-12)** — FULL tier only, one-shot
  fallback kept; the local Kong proxy buffers SSE, so it's verified against cloud, not local.
- **Glasses native captures stay native; the app listens + enriches** (never intercept firmware
  capture; preserve video for V2).

---

## 8. Working conventions (how this project operates)

- **Document all confirmed research/findings in the repo the same session** (director's standing
  rule). Protocol findings → `docs/recon/*`; state → `PROJECT_STATUS.md` + this file; plan → roadmap.
  Date verified-on-device claims.
- **Verify on device before claiming done.** Build + install + exercise the real path; report the
  evidence. Don't claim a latency/voice win that needs the director's voice without saying so.
- **Commit discipline:** small, scoped commits per increment; message ends with
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Branch is `master`.
- **Keep this handoff + `PROJECT_STATUS.md` current at the end of each session** — that's what makes
  the next handover seamless.

---

## 9. Open issues / risks to carry forward

- **Verified on device vs cloud (2026-06-12):** OTP sign-in (real code email via Resend) and the
  streamed, spoken Ask answer. Two OTP bugs found+fixed on the way: the project `Json{}` has
  `encodeDefaults=false`, so defaulted DTO fields are DROPPED from payloads (verify's
  `type:"email"` never got sent) — auth DTOs now take those fields explicitly; and verify errors
  now surface GoTrue's real message. **Watch for the encodeDefaults trap in every new request DTO.**
- **Capture/upload paths not yet exercised against cloud** (remember/photo/audio sync) — next
  glasses session; also still owed: voice-path `EchoLatency` numbers with streaming on.
- ~~UI read-only engine queries~~ **DONE (director-approved, 2026-06-12):** `MemoryStore.recent()`
  / `.mediaMemories()` (+ `MemoryDao.recentMedia`), `toMemory()` carries createdAt + syncState/
  localMediaPath via `Memory.metadata`. Timeline is the real day-grouped river with read-only
  semantic search (never speaks); Gallery is a 3-up local-thumbnail grid. Device-verified.
  Memory **detail** screens (memory_detail_photo/_voice_note) still unbuilt — needs signed-URL
  media viewing; build when tackling that.
- **Designed screens not yet built** (features don't exist yet): onboarding wizard, Help & Learn
  center (the "?" shows a stub dialog), translation/OCR/meeting consoles, Ask-Jarvis (V2),
  notification showcase. Build each alongside its feature.
- **Pixel screenshots via adb:** use Git Bash (`adb exec-out screencap -p > x.png`) — PowerShell
  `>` corrupts binary output (UTF-16 re-encode).
- `supabase config push` / local `supabase start` need `RESEND_API_KEY` exported from
  `supabase/.env` first, or the `env()` substitution comes up empty (local dev never sends email,
  so only config push really cares).
- **No foreground service** yet → button reactions need the app foregrounded to be reliable.
- **Off-grid voice** has no on-device dictation → falls back to typing.
- **Dev flavor** keeps the hardcoded login + cleartext to 127.0.0.1 by design; prod compiles both out.
- **16 KB page warning** (Vosk libs) on Android 16 launch — dismissable now; matters for Play (Phase G).
