# JARVIS — Session Handoff (read this FIRST)

*The single-source handover between sessions. A new session should be able to read only this and
know exactly where we are, how to run/verify, what not to relitigate, and what to do next.*
*Last updated: 2026-06-13. Keep this current at the end of every working session.*

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
| **E** real users | 🟢 **core done (2026-06-12)** | Cloud `jarvis-prod` fully deployed (migrations/secrets/6 functions); dev/prod flavors; **director signed in on device via emailed 6-digit code (Resend)** and got a streamed spoken answer. Remaining: Google One-Tap, function rate limits. |
| **B+** foreground service | 🟢 **built + device-verified (2026-06-13)** | `ConnectedCompanionService` (foregroundServiceType=connectedDevice) keeps the glasses link + capture reactions alive with the app backgrounded/killed. Capture pipeline in the shared `GlassesCaptureReactor`. Settings "Keep listening in the background". **BLE auto-reconnects** (backoff + BT-state receiver). **Background capture reaction verified** (app backgrounded → glasses photo → service fired the sync on its own). Fixed a stale-inventory-counter bug that swallowed captures (re-baseline to 0 after each sync). Transfer completion still gated by Wi-Fi-Direct + recording-state flakiness (see gotchas). |
| **E+** hardening / rate limits | 🟢 **done (2026-06-13)** | Per-user hourly Edge Function caps (rate_limits table + check_rate_limit RPC, deployed). Google One-Tap **client built, gated** behind `GOOGLE_WEB_CLIENT_ID` (needs director OAuth setup — §6). |
| **F** privacy / reliability | 🟢 **core done (2026-06-13)** | Mic-hot indicator (red pill when recording), one-time recording-consent gate, GDPR **export** (JSON share) + **delete-everything** (local+cloud, confirm dialog), local **crash telemetry** (`CrashReporter` → filesDir/crash.log, shown in dev console). All device-verified. Remaining F: battery profiling, R8/minify, 16KB Vosk alignment (Phase G). |
| **UI** design integration | 🟢 **core done (2026-06-12)** | Steps 1–5 of the director's UI plan: tokens → JarvisTheme (M3, dark-only, tri-font, amber via CompositionLocal) → shared components → animated PresenceOrb (6 states) → designed screens (Live console ×4 variants, Timeline river, Gallery grid, photo/video **detail** with delete, Settings) wired to HomeViewModel. Dev console preserved under Settings → Developer console. Library has read-only `recent`/`mediaMemories` queries; **delete** (local+cloud) and **video playback** added. **Help & Learn center** (the "?" → Hub/Gestures/Guides/Say, real decoded content) and the **6-step onboarding wizard** (Welcome→SignIn→Permissions→FindingGlasses→HardwareCheck→WakeWord, first-run gated) both built + **device-verified 2026-06-12**. |

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
- **`:app` capture pipeline + background (2026-06-13):**
  - `GlassesCaptureReactor.kt` — **the shared, injectable (`@Singleton`) autonomous capture
    pipeline**, extracted from `HomeViewModel`. Listens to `ble.notifications`; on a button-press
    event runs the sync ceremony (BLE→Wi-Fi Direct→HTTP pull) then enriches each file into a memory
    (photo→caption, AI-gesture→spoken Look&Ask, audio→transcribe). Refcounted `start()/stop()` =
    one shared collection across hosts (UI + service), no double-processing; a mutex serializes
    auto-capture vs manual sync. **`start()` calls `ble.connectGlasses()`** — without it, foreground
    button presses never reach the app. Exposes `status`/`working`/`lastAnswer` StateFlows the UI
    mirrors. `HomeViewModel` delegates here (`syncGlasses`, reconcile, capture reactions).
  - `ConnectedCompanionService.kt` — foreground service (`foregroundServiceType=connectedDevice`)
    that hosts the reactor + holds the BLE link with **no UI** (app backgrounded/killed). Ongoing
    notification reflects pipeline status; `START_STICKY`. Toggled by Settings "Keep listening in
    the background" (`CompanionPrefs`); `AppRoot` restarts it on launch when enabled + signed in.
  - `ui/help/HelpScreen.kt` (Help center, opened from the "?"), `ui/onboarding/` (first-run wizard
    + `OnboardingPrefs`).
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
- **`adb shell pm clear` revokes runtime permissions.** It wiped ACCESS_FINE_LOCATION +
  NEARBY_WIFI_DEVICES (which the app didn't re-request — MainActivity now requests all five),
  silently breaking Wi-Fi Direct discovery → every glasses sync failed. If sync mysteriously
  dies, check `dumpsys package com.echo.companion | grep granted=false` first.
- **Cleartext must stay enabled in ALL flavors:** the glasses media pull is plain HTTP over
  Wi-Fi Direct (`http://192.168.49.x/files/…`, firmware constraint). The prod flavor briefly
  shipped `usesCleartextTraffic=false` and every transfer died with "CLEARTEXT … not permitted".
  Cloud traffic is https:// regardless; the flag never downgrades it.
- **The media pull must be bound to the Wi-Fi Direct `Network`** (`GlassesP2pManager.boundNetwork()`
  → the `p2p…` interface). On a phone that also has home Wi-Fi, an unbound socket to the glasses'
  `192.168.49.x` IP routes out the default network and the transfer silently goes nowhere. (The
  original Phase 2 sync only worked because home Wi-Fi happened to be off.)
- **The glasses' embedded HTTP server speaks bare HTTP/1.0 only** (Jieli firmware). OkHttp's
  mandatory 1.1 headers make it hang up with "unexpected end of stream". `MediaTransferClient` now
  uses a **raw socket** with a header-less `GET <path> HTTP/1.0\r\n\r\n`, reading to EOF; OkHttp is
  no longer used for the glasses transfer (kept in the ctor for DI only). Manifest is retried up to
  6× (2 s apart) for AP warm-up. Watch `EchoMedia` logcat — it logs the server's status line.
- **The glasses steal the phone's AUDIO whenever they're powered on** (BT classic SCO/A2DP —
  independent of the BLE control link): the mic records from the glasses and TTS answers play in
  the glasses speaker. Testing on the phone with the glasses on a desk looks like "nothing works"
  (silent recordings → "Didn't catch that"; answers inaudible). Power the glasses off to test
  phone-only. The Live console now reads the true audio route (`rememberGlassesAudioConnected`)
  and appends "audio in glasses" to the always-visible status line.
- **Glasses button reactions need the GATT link UP first.** The app can only receive button-press
  notifications (capture, double-click-BACK Look&Ask) while connected over BLE. `GlassesCaptureReactor
  .start()` connects on launch, and the link now **auto-reconnects** (2026-06-13): on a dropped
  GATT callback (post-Wi-Fi-transfer / out-of-range / timeout) it retries with capped backoff
  (2/4/8/16/30s); a `BluetoothAdapter` state receiver also reconnects when BT is toggled back on
  (BT-off delivers no disconnect callback). `disconnectGlasses()` tears down when the last reactor
  host detaches. Device-verified self-heal across a BT off/on cycle.
- **A recording in progress hijacks the BACK button.** Native firmware: hold BACK = start audio
  recording; single-click BACK *while recording* = stop. So if a recording is live (you'll see
  `0B` RecordingTick heartbeats in `EchoBle` logcat), a "double-click BACK" manages the recording
  instead of firing the AI gesture (`0x02`) → **Look & Ask won't trigger until the recording is
  stopped.** Confirmed live 2026-06-13.
- **Decoded device gotchas** (full detail in `docs/recon/Glasses_Controls.md` §4): glasses **delete
  their files after a successful sync** and emit a zero-inventory event (only an inventory *increase*
  = a new capture); `BC 41` frames on `de5bf729` are **command-ACK echoes, NOT events** (parsing
  them caused a capture storm); audio records as `.opus` (route as `audio/ogg`); the Wi-Fi-start ACK
  leaks the P2P SSID+passphrase in cleartext.

---

## 6. What's NEXT — the critical path

### 🎯 IMMEDIATE NEXT (director, 2026-06-13): Agent Delegation (heavy tasks via Claude Code)
The big new direction: JARVIS as a **chief of staff that acts** — delegating heavy multi-step tasks
(research → coding → email/calendar, in that order) to **Claude Code on the director's Max
subscription**. Fully designed in **`docs/AGENT_DELEGATION.md`** (Phase 1 local bridge → Phase 2 hosted
+ async; subscription/ToS clarified: running Claude Code the product on the sub is legit, distinct from
raw-API-via-sub). **Build starts at M0** (a local Agent Bridge wrapping `claude -p`) after director
review. Vision/roadmap context: `docs/ASSISTANT_ROADMAP.md`, `docs/ASSISTANT_MEMORY.md`.

**✅ DONE THIS SESSION — Assistant Memory v1 (Hermes/OpenClaw pattern), DEPLOYED to prod + verified:**
- **Profile layer** — `profile` table (SOUL + curated user facts) injected into `chat`/`chat-stream`
  with a **non-negotiable TRUTH charter** (never fabricate; "I don't know" then go find out). SOUL
  seeded from `docs/assistant/SOUL.md` (director to edit). Verified: in-character + admits ignorance.
- **Distillation** (`distill` fn) — extracts durable facts from a finished conversation into the
  profile instead of hoarding raw Q&A; app fires it at conversation end. Verified end-to-end.
- **Explicit memory** (`remember` fn) — "remember that…" pins a fact instantly ("Noted").
- **Editable profile** — Settings → "JARVIS's memory" (view/edit SOUL + facts; `profile` fn).
- Migration + 5 functions deployed (director-authorized). **CAUTION: don't test cleanup against the
  live profile** — a `user_facts=""` wipe during testing cleared the director's real distilled facts
  (Name/children); restoring blocked (can't write unverified personal facts). Director re-adds via
  Settings or by talking.

**🟡 WIP — voice-controlled glasses skill (first v2.1 skill):** "what am I looking at" / "take a photo
of this" → capture + describe aloud (`reactor.captureAndDescribe`, `isVisionCommand`). **Not working on
device yet** — capture times out inside a conversation (camera/Wi-Fi/SCO/BLE contention). Fixes so far:
release held SCO for the capture; ensure GATT link + retry capturePhoto until sent; `EchoVision`
logging. **Needs a focused on-device debugging pass** (pull `EchoVision` right after a fresh attempt).

**Context/memory original ask: ADDRESSED by v1** — within-conversation recall via history threading
(last 3–6 turns), cross-session via distilled profile + RAG, no more verbatim hoarding. Barge-in lives
only in the held-SCO path (narrowband answer audio is the trade-off); fine as-is.

**✅ DONE + VERIFIED THIS SESSION — the conversation stack:**
- **On-device STT (sherpa-onnx / Whisper-tiny.en)** is the **primary** transcriber — offline, no
  quota, **~0.4–0.5 s** per turn (vs 7–13 s cloud). Model **downloads on first run** (~103 MB →
  filesDir, progress in the Live console), cloud STT kept as fallback (`stt-source=device|cloud`).
  Commit `3d8d6e0`. *(Root cause it solved: Gemini free-tier daily quota — ~20/day — ran dry mid-use;
  the app had been silently showing quota 502s as "didn't catch that". See `docs/STT_FAILOVER.md`.)*
- **Barge-in WORKS** (commits `6f839fd`→`62fd991`): talk over JARVIS and it stops + listens. AEC
  cancels the TTS echo to ~50 RMS so detection is clean (fires on ~3–12k voice, no false trips). The
  fix that cracked it: a **hangover counter** (not consecutive frames — speech dips between syllables)
  + low floor (900). Held-SCO full-duplex during the answer (answer plays narrowband).
- **Turn-taking + ending + scrollable transcript** all verified (see detail below).

Voice-quality is fixed (further below). The conversation work made JARVIS a **real back-and-forth**:
after it answers, the mic re-opens for a follow-up, continuing until the director is done.
**Increment A SHIPPED (2026-06-13, commits `6e9a324`+`50f95f6`) — turn-taking + multi-turn + ending.**
`HomeViewModel.converse(continuous)` replaces the old one-shot `doTalk()`:
- **Orb** (`talk()`) and **wake word "Jarvis"** (`startWake`) now start a **continuous conversation**
  — after answering, the mic re-opens for a follow-up and loops until you go quiet (one "still there?"
  reprompt, then a soft close) or say a **closing phrase** (`isClosing()`: thanks/that's all/stop/…).
  The **glasses button** stays **one-shot** (`doTalk() = converse(continuous=false)`).
- **Tap the orb again to end** a conversation (`inConversation`/`stopConversation`); re-entry guarded.
- **Multi-turn context**: threads the last 3–6 turns (cap 12 msgs) via the new `history` field on
  `ChatRequest` (non-defaulted — dodges the `encodeDefaults=false` trap). The `chat`/`chat-stream`
  functions **already** accept `history` and **already persist every Q&A to the memory index**, so
  **no backend changes / no deploy** were needed.
- Verified headless: the loop runs, reprompts, threads context, and ends; **needs a live voice test**
  (real multi-turn feel + the tap-again-to-end, which a fixed adb tap can't hit on the animated orb).

**Ending hardened (commit `027b976`)** — the orb FAB was `enabled=!busy` so it was DISABLED during a
conversation (couldn't tap to stop); now tappable + a dedicated **"End conversation"** button shows in
the Live console. Silence-ending now keys off the VAD's `speechStarted` (not just a weak level guard),
so room noise no longer keeps the conversation alive forever. Closing-phrase match is punctuation/
case-robust. End is responsive: `recordUntilSilence(shouldAbort)` bails within a frame and
`TtsEngine.stop()` cuts speech + unblocks the suspended speak/finishStream.

**Increment B SHIPPED — barge-in (commit `6f839fd`).** You can interrupt JARVIS mid-answer. In a
conversation, SCO is **held open for the whole session** (`BtAudio.begin/endScoSession`) and the answer
plays over the SCO output (`TtsEngine.useCommunicationRoute(true)`; A2DP is suspended during SCO →
narrowband but intelligible). `BtAudio.awaitBargeIn()` monitors the SCO mic with `AcousticEchoCanceler`
+ an echo-aware sustained-speech threshold; `converse()` races the answer against it and, on barge-in,
stops TTS and loops straight into capturing the interruption (cue skipped). Held SCO also makes
follow-ups snappier (no per-turn warm-up). **NEEDS an on-device echo-tuning pass** — the `awaitBargeIn`
threshold (echoFloor×1.8, floor 1100, ~240 ms sustained) is a conservative first guess: too low →
JARVIS cuts itself off on its own voice; too high → misses quiet interruptions. **Verify live, then tune.**

### ✅ DONE (2026-06-13): voice-conversation quality deep-dive
The daily-driver feature set is built; the open problem is **conversation quality** — the director
reports JARVIS "often doesn't pick up what I'm saying and makes too many errors" in spoken use.
**The next session's job is a structured root-cause analysis** of the voice loop (record → VAD →
STT → RAG → TTS): is it the transcription (Gemini STT), endpointing/timing (cut-offs, SCO warm-up),
the RAG/answer grounding, or the speaking environment? Do NOT start building fixes until the
analysis names the dominant failure mode with evidence (logged transcripts vs. ground truth).

**STATUS 2026-06-13 — DOMINANT FAILURE MODE NAMED (capture session 1 done).** Full evidence in
`docs/VOICE_QUALITY_INVESTIGATION.md` (12 real turns, prodDebug/cloud, director wearing glasses).

**Verdict: the VAD endpointer (`BtAudio.recordUntilSilence`) is the dominant failure — NOT the mic,
NOT STT.** (1) `silenceMs=700` cuts speech off on natural pauses and the rest of the utterance is
never recorded → proven word loss ("…at gate forty two", "…about dinner on Friday", "…five" all
spoken, never captured; "one two three four five" split into "one"/"two three four"). (2) The
one-shot 250 ms noise calibration intermittently caps the threshold at 2500 and misses real speech
entirely (`noSpeechTimeout` on audio that plainly contained speech). Compounders: the **earcon never
reaches the glasses** (director: no beep on any turn — `ToneGenerator` route bug), the blind 1.5 s
SCO warm-up clips onsets, and **Gemini STT hallucinates confident transcripts from silence** ("Trees
that I mean to look you to your car") so the blank-guard never fires. **Mic = mSBC WIDEBAND 16 kHz
(BT-stack log: `sco_codec=2`, `bt_wbs=on`) → hypothesis #1 REFUTED.** STT near-perfect on the one
clean full-length turn.

**FIXES SHIPPED + VERIFIED (session 2, commit `5fb3986`).** `BtAudio.recordUntilSilence` reworked:
trailing silence 700ms→1500ms, noSpeechTimeout 4s→7s, robust low-percentile + rolling noise floor
(was a fragile one-shot 250ms), speech-start debounce, SCO-hot warm-up flush, and an audible
**listening cue over the SCO route** (`cueListening`). `doTalk` adds a silence guard (near-silent
captures never reach the LLM, killing the STT-hallucination errors) + a spoken "didn't catch that".
**Device-verified:** counting 1→10 with pauses and a 14s sentence with deliberate stops both captured
whole and transcribed perfectly (session 1 cut them to "one"). Premature cut-off eliminated.

**OPEN follow-up:** the in-ear cue is too faint on these glasses (SCO call channel is quiet) — louder
two-beep shipped, **pending re-verification**; if still faint, boost `STREAM_VOICE_CALL` volume during
the cue or repeat it. Consider raising maxMs (one long turn hit the 15s cap). Full evidence +
before/after table in `docs/VOICE_QUALITY_INVESTIGATION.md` (sessions 1 & 2).

Instrumentation (debug-only, **temporary — remove when this work closes**): per-turn WAV + `index.tsv`
+ `EchoVoice` logcat (`HomeViewModel.dumpVoiceDebug`), VAD stop-reason/threshold on `Recording`,
Node spectral analyser `scripts/analyze_wav.mjs` (its cutoff metric is unreliable on real speech —
trust the BT-stack codec log: mic is confirmed **mSBC wideband 16kHz**).

### Status as of 2026-06-13 — what's DONE (don't redo)
Phases 0–2, A, B (+foreground service), C, D2, **E** (cloud/auth/Resend OTP/streaming), the full
**UI** skin (theme/orb/screens/help/onboarding), **branding**, **rate limits**, **Phase F** core
(mic indicator, recording consent, GDPR export/delete, crash telemetry), and **BLE auto-reconnect**.
All device-verified. Remaining engineering: Google One-Tap (director OAuth setup), Phase G (Play
release: R8, 16KB Vosk alignment, signing, data-safety), Phase H (ops). The **V2 "Ask Jarvis"
deliberate lane** is the bigger conversational feature — gated on the voice-quality analysis first.

**Two finish lines, be explicit about which you're targeting:**
- **"You can wear it every day" (personal daily-driver):** essentially reached — cloud, real login,
  UI, foreground service, privacy all done. The remaining gap is conversation quality (above).
- **"A stranger installs from Play":** Phase G/H — release engineering, ops (careful, not risky).

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

**Google One-Tap (client built 2026-06-13; DIRECTOR setup to activate):** the
"Continue with Google" button + Credential Manager flow + `EchoBackend.signInWithGoogle`
ship gated behind `GOOGLE_WEB_CLIENT_ID` (empty ⇒ hidden). To turn it on:
1. **Google Cloud Console** → create an OAuth consent screen; create an **OAuth Web client ID**
   (the "server" client) and an **Android client ID** (package `com.echo.companion` + the signing
   SHA-1 from `keytool`/Play).
2. **Supabase** → Authentication → Providers → **Google**: enable, paste the Web client ID +
   secret; add the Android client ID under "Authorized Client IDs".
3. Set `GOOGLE_WEB_CLIENT_ID` in `android/app/build.gradle.kts` (defaultConfig) to the **Web**
   client ID, rebuild. Button appears automatically.

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
