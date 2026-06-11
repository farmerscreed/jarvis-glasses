# JARVIS — Production Roadmap (start → finish)

*From "working vertical slice on my desk" to "an app a stranger can install, pair, and trust."*
*Companion to `PROJECT_STATUS.md` (current state) and `docs/recon/01_IMPLEMENTATION_PLAN.md` (original feature plan).*
*Written 2026-06-11.*

---

## 0. What "production ready" means for THIS product

JARVIS is not a normal app. It's a **wearable companion**: it must work while you walk through a
parking garage with one bar of signal, while you're on a plane, while your phone is in your pocket
and your hands are full. So our definition of done has five pillars:

| Pillar | The promise to the user |
|---|---|
| **P1. Never lose a memory** | Anything you capture (photo, voice, note) is durably saved the instant it happens — internet or not. |
| **P2. Always answers something** | Ask a question in a tunnel and Jarvis still responds — maybe simpler, never silent. |
| **P3. Hands-free first** | The glasses buttons and the wake word drive everything; the phone screen is optional. |
| **P4. Private by construction** | Your eyes and ears stream through this thing. Row-level security, private buckets, on-device options, delete-everything button. |
| **P5. Boring reliability** | Survives BT drops, app kills, reboots, low battery, Doze mode. No babysitting. |

Everything below ladders into those five pillars.

---

## 1. The map (phases at a glance)

```
 NOW ──► A. Close the slice        (image persistence — in flight, days)
        B. Hands-free spine        (glasses-button reactions, auto-sync)
        C. OFFLINE-FIRST REBUILD   (local-first memory core — the big architectural move)
        D. Latency war             (VAD, streaming STT/TTS; 12s → ~3s)
        E. Real users              (auth, onboarding, pairing wizard, cloud Supabase)
        F. Hardening               (reliability, battery, security, privacy/legal)
        G. Release engineering     (signing, R8, 16KB, Play listing, beta)
        H. Ops & cost              (monitoring, quotas, kill switches)
        UI ─ runs in parallel from C onward (design externally, integrate after engine)
        V2 ─ video, meeting capture, translation, OCR (out of scope here, noted at end)
```

Suggested ordering rationale: **A** is half-done (storage migration exists uncommitted; `media_path`
is silently dropped in `IngestRequest`). **B** is the riskiest unknown (BLE notification recon) and
defines the product's soul. **C** must come before E — retrofitting offline-first after real users
have cloud-only data is far more painful than building it now, and it reshapes every pipeline that
D–F then harden. UI integration lands after C/D so the design wraps a stable engine.

---

## 2. Phase A — Close the vertical slice (image persistence)

*Status: storage buckets migration written (uncommitted). App-side upload missing. Known bug:
`IngestRequest` drops `media_path`.*

- [ ] Apply + commit `20260611110843_storage_media_buckets.sql`.
- [ ] `EchoBackend.uploadMedia(file): String` — PUT to `storage/v1/object/media/<uid>/<yyyy-MM>/<filename>`
      with the user JWT (RLS enforces ownership). Return the object key.
- [ ] Fix `IngestRequest` to carry `media_path` (+ optional `lat`,`lng`,`tags`); thread through
      `EchoBackend.remember()`.
- [ ] `lookAndAsk()` / sync flow: upload first, then ingest with the *storage key* (not local filename).
- [ ] Read path: signed URLs (60 min) via `storage/v1/object/sign` for any UI that shows images.
- [ ] Minimal gallery check: list memories where `media_path is not null`, render thumbnails.
- **Exit test:** capture on glasses → sync → row in `memories` has a storage key → signed URL renders
  the photo on another device/session.

## 3. Phase B — The hands-free spine (glasses buttons)

*The feature that turns "an app that controls glasses" into "glasses that drive the app."
Design already decided in `PROJECT_STATUS.md` §6 + `docs/recon/Glasses_Controls.md`.*

1. **Recon session (one sitting):** subscribe to all notify characteristics on service `de5bf728`
   (esp. `de5bf729`), press every physical gesture (front/back, short/long/double), log `EchoBle`
   bytes, table them in `docs/recon/Glasses_Controls.md`.
2. **Event router:** `GlassesEventBus` in `:device:ble` — parse notification → typed event
   (`PhotoCaptured`, `VideoCaptured`, `AudioClipCaptured`, `AiGestureTriggered`…).
3. **Reactions (initial mapping):**
   - photo captured → auto-sync that file → ingest as PHOTO memory (with embedding of a short
     auto-caption) → optional earcon "saved".
   - AI gesture → auto-sync → Look & Ask → speak answer.
   - audio clip → auto-sync → transcribe → ingest as VOICE_NOTE memory.
4. **Keep firmware behaviors native** (music/volume/video) — we listen and enrich, never intercept.
5. **Foreground service:** reactions must work with the app backgrounded → a `ConnectedCompanionService`
   (foreground, `connectedDevice` type) owning BLE subscription + sync queue.
- **Exit test:** phone in pocket, screen off. Press glasses button at a parked car → hear "saved".
  Later: "Jarvis, where did I park?" → answer references the photo memory.

## 4. Phase C — OFFLINE-FIRST REBUILD (the architectural keystone)

*The user constraint: the phone may have **no internet** or **slow internet**, and the device must
remain useful. Good news: the hardware was accidentally designed for this — **every glasses↔phone
link is local** (BT audio, BLE control, Wi-Fi Direct media), the wake word (Vosk) is already
offline, and Android TTS is on-device. Only two things currently require the cloud: embeddings
(Gemini) and the brain (Claude). So we re-architect around a local-first core and treat the cloud
as an enhancer, not a dependency.*

### 4.1 The three-tier capability model

A `ConnectivityGovernor` continuously scores the link (reachability + RTT probe to our API +
metered/unmetered) and pins the pipeline to one of three tiers. Every feature declares behavior
per tier. The UI surfaces the tier as a calm status chip, never an error.

| | **TIER 1 — FULL** (good net) | **TIER 2 — LEAN** (slow/flaky net) | **TIER 3 — OFF-GRID** (no net) |
|---|---|---|---|
| Capture (photo/audio/note) | ✅ instant, synced | ✅ instant, queued for upload | ✅ instant, queued |
| Memory write | cloud ingest + local mirror | **local first**, outbox drains opportunistically | local only, outbox waits |
| Memory recall | pgvector (cloud) | local vector index, cloud refresh in background | local vector index |
| STT | Gemini 2.5-flash | Gemini w/ tight timeout → fallback local | **Vosk full dictation** (lib already shipped!) |
| Brain | Claude (full RAG) | Claude, text-only, compressed context, hard timeout → fallback local | **Jarvis Lite**: on-device small LLM over recalled snippets + rule-based intents |
| Vision | Claude vision (resized image) | deferred: "I'll look at this properly when we're back online" + local caption stub | deferred, queued |
| TTS | Android on-device (already) | same | same |
| Wake word | Vosk (already offline) | same | same |

### 4.2 Local-first memory core (the big build)

- **Room database** on the phone becomes the *primary* write target: `local_memories` mirroring the
  server schema + `outbox` table (op, payload, media file ref, retry count, created_at).
- **Outbox drain** via WorkManager: network-constrained worker uploads media → calls `ingest` →
  marks row `synced` with the server id. Idempotency key = client-generated UUID (server upsert on
  it) so retries never duplicate. Media uploads additionally constrained to unmetered-or-charging
  when files are large.
- **On-device embeddings for offline recall:** ship a small text-embedding model
  (Google **EmbeddingGemma ~300M** via MediaPipe / LiteRT, or a TFLite MiniLM) and embed every
  memory locally at write time. Local vector search = brute-force cosine over a few thousand rows
  (trivial on a Pixel 8; add a tiny HNSW lib only if it ever isn't).
- **Dual-embedding strategy:** the local vector is for offline recall only; on sync the server
  re-embeds with Gemini 1536-dim as the canonical vector. The two spaces never mix, so no migration
  hazards.
- **Conflict policy:** memories are append-only events → no real conflicts. Last-writer-wins on the
  rare mutable fields (tags). This is why local-first is *cheap* for us — do it now, not later.
- **Catch-up choreography:** on tier upgrade, drain outbox oldest-first, then pull recent server
  memories to refresh local cache, then process the deferred-vision queue.

### 4.3 Jarvis Lite (the offline brain) — brainstormed options, recommendation included

1. **On-device small LLM (recommended):** Gemma 3 1B (or 3n) via **MediaPipe LLM Inference API** /
   LiteRT. Pixel 8 runs 1B comfortably (4-bit, ~1–2 GB). Persona-prompted "Jarvis Lite": answers
   simple questions over the top-K locally-recalled snippets, admits limits ("I can answer fully
   when we're back online"). Model downloaded as an optional **"Offline Pack"** in settings
   (~1–2 GB, on Wi-Fi, like an offline maps download).
2. **Rule-based intent floor (always present, even without the pack):** tiny grammar for the
   high-value verbs — *take a note / remember this*, *what's the last thing I saved*, *where did I
   park* (recall + read top hit), *what time is it*, *read that back*, *sync my glasses*. Vosk STT →
   keyword intent → canned-template TTS. Costs nothing, works on any device.
3. **(Considered, rejected for V1)** Whisper.cpp for offline STT — better accuracy than Vosk but
   heavier; Vosk is already integrated and good enough for command-style speech. Revisit if offline
   dictation quality becomes the complaint.
- **The honest contract** (also the UX copy): *off-grid Jarvis hears you, saves everything, recalls
  anything, and answers simply; the deep reasoning and vision catch up the moment you're back online.*

### 4.4 LEAN tier tricks (slow / flaky internet)

- **Adaptive timeouts with local fallback:** Claude call races a 6–8 s budget; on miss, Jarvis Lite
  answers from the same recalled snippets and the full answer is dropped (no confusing double-reply).
- **Payload diet:** resize/compress photos before vision calls (≤1280 px, JPEG q70 — cuts upload
  ~10×); text-only RAG context (no images) on LEAN; cap recalled snippets.
- **Priority lanes:** interactive requests (user is waiting, ears on) preempt background sync;
  background media uploads pause while a voice exchange is in flight.
- **Response cache:** recent Q→A pairs cached locally; identical re-asks answer instantly.
- **Single-flight + hedging:** never two concurrent identical calls; optionally hedge STT across
  Gemini/local and take the first.

### 4.5 Modality fallback — when VOICE itself fails (not just the network)

*Same graceful-degradation philosophy as the tiers, applied to input/output. Voice can fail for a
dozen reasons: SCO won't route, BT drops mid-question, a loud street defeats STT, TTS engine
missing, the user is in a meeting and can't speak, or simply has no voice that day. The rule:*

> **Every voice action has a touch/text twin, and every spoken answer has a readable twin.**

- **Type-to-Jarvis:** a text input on the Home screen feeding the *same* RAG pipeline as the voice
  path (skip STT, optionally skip TTS). This is the universal backstop — if every radio and codec
  on the phone misbehaves, typing still works.
- **Answers are always rendered**, not only spoken: the transcript card on Home is the canonical
  output; TTS is an enhancement. TTS failure → text + a notification, never silence.
- **Mic fallback chain:** glasses SCO → phone mic → typed input. Auto-advance down the chain on
  failure, with a small chip showing which source is live ("Mic: glasses / phone / keyboard").
- **Manual twins for every glasses action:** on-screen Capture, Sync, Look & Ask, Take-a-note
  buttons stay forever — they already exist; they are the fallback, never remove them.
- **Failure taxonomy with recovery copy, not errors:** empty/low-confidence STT → "I didn't catch
  that — try again or tap to type"; SCO route fail → auto-switch to phone mic + chip flips; BT
  drop mid-exchange → answer still completes on phone speaker + notification.
- **Notifications as the second output channel** when the app is backgrounded (answer arrives as
  an expandable notification with the text).
- **Exit test:** turn glasses off entirely → the whole product (capture excepted) remains usable
  from the screen: type a question, get a written answer, browse and search memories.

### 4.6 Exit tests for Phase C (run them for real)

- Airplane mode ON → press glasses button at the car → sync over Wi-Fi Direct still works (it's
  local!) → "Jarvis, take a note: parked level 3" → note saved → "where did I park?" → spoken
  answer from local recall. Airplane mode OFF → outbox drains, server row appears, deferred vision
  runs.
- Throttle to 2G (or `adb` network shaping) → voice question answers within 10 s via fallback, no
  hang, no crash.

## 5. Phase D — The latency war (12 s → ~3 s perceived)

*Current: fixed 5 s recording + sequential STT→LLM→TTS ≈ 10–15 s. Target: ≤3 s from end-of-speech
to first spoken word on FULL tier.*

1. **VAD endpointing (do first, biggest win/effort ratio):** stop recording on ~700 ms of trailing
   silence instead of a fixed 5 s (WebRTC VAD or Silero-VAD tflite). Saves up to ~4 s alone.
2. **Streaming STT:** stream audio chunks as they record (Gemini Live API, or swap STT to a
   streaming provider) so the transcript is ready ~instantly at end-of-speech.
3. **Streaming LLM → sentence-chunked TTS:** stream Claude tokens; speak sentence 1 while sentences
   2+ generate. Perceived latency = time-to-first-sentence.
4. **Warm paths:** keep SCO pre-negotiated while in "hands-free" mode; reuse HTTP/2 connections;
   pre-fetch top-K recall *while the user is still speaking* (embed partial transcript).
5. **Earcons:** a soft "listening" / "thinking" sound so silence never feels like failure — cheap,
   huge perceived-latency win.
- **Instrument it:** log per-stage timings (record / stt / recall / llm-first-token / tts-start) to
  a local trace; you can't win a war you can't measure.

## 6. Phase E — Real users (auth, onboarding, cloud)

- **Cloud Supabase:** create project → `supabase link` → `db push` (the migrations were built for
  this). Set function secrets via `supabase secrets set`. Enable PITR backups. Keep local stack as
  the dev environment; introduce `prod`/`dev` build flavors with different base URLs.
- **Real auth:** Supabase Auth — **email OTP (passwordless)** + **Google One-Tap**. Kill the
  hardcoded test login. Session refresh handled in `EchoBackend`; biometric app-lock optional.
- **JWT verification on every Edge Function** (verify_jwt = true), rate limiting per user.
- **Onboarding + pairing wizard** (this is half of UX success for a hardware companion):
  welcome → sign in → BT permissions explainers (why each one, Android 12+ runtime perms) →
  find & bond glasses → test sound → test capture → test sync → "say *Jarvis*" → done.
  Each step verifies for real and offers a fix-it path (the #1 support topic will be pairing).
- **Multi-device sanity:** memories are server-truth + local cache, so a second phone just works;
  glasses bond is per-phone.

## 7. Phase F — Hardening (reliability, battery, security, privacy)

### Reliability
- `ConnectedCompanionService` foreground service: owns BLE connection + auto-reconnect with
  exponential backoff; survives app swipe-away; restarts on boot (with user toggle).
- BLE/P2P state machines with explicit timeout/retry/teardown on every step of the
  BLE→Wi-Fi-start→IP-notify→P2P-connect→HTTP-pull chain (mirror the stock app's retries);
  dedup ledger so re-syncs never duplicate (hash or filename+size against an `imported_files` table).
- Crash + error telemetry: **Sentry** (or Crashlytics) with PII scrubbing; breadcrumbs for the
  BLE/P2P state machines — remote diagnosis of pairing problems will live or die on this.
- Chaos drills as repeatable tests: kill app mid-sync, toggle BT mid-recording, reboot mid-outbox,
  Doze deep-sleep with pending outbox.

### Battery
- Wake-word duty cycle (Vosk on phone mic is the main drain): auto-pause during screen-off
  inactivity windows, or "hands-free only while charging / headphones" presets — measure first
  with Battery Historian, then tune.
- Batch outbox drains; defer big uploads to unmetered+charging (WorkManager constraints already
  planned in C). No persistent SCO.

### Security
- All prod traffic TLS; delete the `cleartextTrafficPermitted` dev exception from the release
  manifest (keep it debug-only for 127.0.0.1).
- Storage stays private-bucket + short-lived signed URLs; never a public bucket.
- Provider keys live only in Edge Function secrets (already the pattern — keep it).
- R8/ProGuard on release; certificate pinning for the Supabase domain (optional, nice-to-have).
- A `SECURITY.md` + key-rotation note (we ship a hardware-adjacent product; people will probe it).

### Privacy & legal (do not skip — this product records audio and takes photos)
- **Recording consent:** one-party/two-party consent laws vary by region → ship a clear in-app
  notice + a hardware-style earcon/LED convention for when the mic is hot; meeting-capture (V2)
  needs an explicit "I have consent" gate.
- **GDPR-grade controls:** export-all (JSON + media zip) and **delete-everything** (DB rows +
  storage objects + local cache) — build as an Edge Function early; it's cheap now, painful later.
- Privacy policy + data-flow diagram (what leaves the phone, to which provider, retained how long).
  Note for the listing: audio snippets go to Google (STT), images/text to Anthropic (reasoning).
- On-device-only mode (TIER 3 forced manually) doubles as the privacy mode — market it as a feature.

## 8. Phase G — Release engineering

- **16 KB page alignment:** required for Play targeting Android 15+ devices. Vosk's `libvosk.so` /
  `libjnidispatch.so` are the offenders. Options: rebuild Vosk with NDK r27+ alignment flags, or
  swap wake word to a 16 KB-clean engine (e.g. ONNX-runtime-based openWakeWord) — decide during C
  since the offline pack work touches the same area.
- Release signing (Play App Signing), `minifyEnabled` + resource shrinking, baseline profiles for
  startup, versioning scheme, CI lane (GitHub Actions: build + unit tests + lint on PR).
- **Play listing realities:** sensitive-permission declarations (mic, nearby devices, location-ish
  Wi-Fi Direct), data-safety form (matches the privacy policy), foreground-service-type
  justification video — budget a week of back-and-forth with review.
- **Beta ladder:** internal testing (you + 2–3 friendly users with glasses) → closed track →
  staged rollout. Gate each promotion on the Phase C/D exit tests passing on at least two
  different phone models (BLE stacks differ wildly across OEMs — test a Samsung).

## 9. Phase H — Ops & cost

- **Cost model per active user/day** (estimate, then measure): STT minutes (Gemini), Claude tokens
  (chat + vision), embeddings (free tier today), storage GB. Add a per-user daily budget in the
  Edge Functions (simple counter table) with a polite "Jarvis needs a rest" over-budget response —
  this is the kill switch against both runaway bills and abuse.
- Provider-outage fallbacks are free: the Phase C tier machinery treats "Claude down" the same as
  "no internet" → Jarvis Lite answers, deep work queues.
- Dashboards: Supabase logs + a tiny `usage_events` table (feature, tier, latency, tokens) →
  weekly look. Alert on error-rate spikes and function cold-start anomalies.
- Support loop: in-app "report a problem" that attaches the last 100 scrubbed breadcrumbs.

## 10. The V2 shelf (explicitly NOT in this roadmap)

Video processing (firmware already captures it; we preserve files), Meeting Capture (audio bucket
already exists in the storage migration), Read-it-to-me / OCR (Look & Ask + OCR prompt — nearly
free once C/D land), Translation (sign + live conversation), iOS.

### The North Star beyond V2: a quiet Machine of One (the *Person of Interest* direction)

Everything in this roadmap makes Jarvis a brilliant **responder**. The PoI-grade leap is becoming
a **noticer** — intelligence that volunteers, not just answers. We are deliberately building the
substrate for it:

1. **Context streams** (cheap, phone-native): location + geofences, time/calendar, glasses state,
   capture activity, connectivity tier. Logged as low-cost context events alongside memories.
2. **Pattern mining over the Memory Index** — the asset PoI's Machine never had: *consensual ground
   truth about one person's life*, already embedded and searchable. Recurring places, rhythms,
   open loops ("you said you'd return this"), stale intentions.
3. **The noticing loop:** a periodic background job that asks one question — *"given the current
   context and the memory index, is there anything worth saying right now?"* — with a strict
   **interruption budget** (e.g. ≤2 proactive utterances/day, quiet hours, confidence threshold).
   The hard part is not generating observations; it's earning the right to interrupt.
4. **Anticipation surfaces:** a morning briefing ("you parked on level 3; the thing you photographed
   for Ade is still unsent"), leave-geofence nudges, "last time you were here you noted…".
5. **The inversion that makes it ours:** PoI's Machine watches *everyone, covertly, for the state*.
   Jarvis is a **Machine of One** — it sees only what its one user chose to capture, runs under
   their keys and RLS, and can be switched to on-device-only at will. Watching *with* you, not
   *over* you. That constraint isn't a limitation of the vision; it's the version of the vision
   worth building.

---

## 11. UI/UX — design vision & external-design workflow

### 11.1 Design thesis: **"The Companion Console"**

The glasses are the product; the phone app is the *console* — a calm, dark, ambient surface you
glance at, not a destination you live in. Three design laws:

1. **State over chrome.** The hero of the home screen is a single animated **presence orb** that
   *is* Jarvis: idle (slow breathing glow) → listening (ripple toward you) → thinking (orbiting
   particles) → speaking (waveform pulse) → off-grid (amber ember, still alive). Connection truth
   lives in two quiet chips under it: `Glasses · connected` and `Cloud · full / lean / off-grid`.
2. **Memory is a river.** The second surface is the **Timeline** — a reverse-chronological river of
   memory cards (photo, voice note, Q&A exchange, place) grouped by day, with one natural-language
   search field at the top ("the bookshop with the green door") that *talks to the same recall
   engine as the voice path*. Unsynced cards carry a subtle amber "on phone, will sync" tick —
   offline is a designed state, never an error state.
3. **Every state is first-class.** Offline, lean, syncing, glasses-disconnected, mic-hot — all
   designed from day one with their own visual voice (amber = local/pending, cyan = live/cloud,
   red reserved for true errors only).
4. **Voice is the front door, never the only door.** Every voice action has a visible touch/text
   twin: a type-to-Jarvis input lives on Home next to the Talk button, every answer is rendered as
   a transcript card (speech is the enhancement), and a small chip shows the live mic source
   (glasses / phone / keyboard). If audio dies entirely, the app remains a complete product.

**Screens (8):** ① Home/Live (orb, chips, Talk, last exchange transcript) · ② Timeline + NL search ·
③ Memory detail (photo hero, description, map chip, related memories, play button for audio) ·
④ Gallery grid · ⑤ Sync/Captures queue (what's on the glasses vs. imported, auto-sync toggle) ·
⑥ Glasses device page (battery, buttons map, connection doctor) · ⑦ Settings (account, offline
pack download, hands-free presets, privacy: export/delete) · ⑧ Onboarding/pairing wizard (6 steps).

**Visual language:** dark-first. Ink `#0B0F14` base, deep navy elevation, **electric cyan**
`#3DDCFF` as the single accent (the Jarvis arc), **amber** `#FFB454` exclusively for
offline/pending semantics. Material 3 components & motion (the engine is Jetpack Compose — this
makes integration nearly mechanical), expressive display type for the orb screen (e.g. Space
Grotesk), Inter for body, a mono face for live transcripts. Motion: slow, breathing, never busy.

### 11.2 Recommended workflow & tool

- **Primary recommendation: Google Stitch** (stitch.withgoogle.com) — it generates *mobile-native,
  Material 3* screens and exports to Figma. Since our engine is Compose + Material 3, its output
  maps almost 1:1 onto what I'll build (colors → `ColorScheme`, components → Compose M3 widgets).
- **Lovable** works too if you want a *clickable* prototype to feel the flows — but it produces a
  React web app, so treat its output as a visual/interaction spec, not code. (v0 and Figma Make are
  similar alternatives.)
- **The loop:** you take the prompt below → generate screens → iterate on taste (colors, density,
  the orb's character) → export/share screenshots or the Figma file back to me → I extract design
  tokens (palette, type scale, spacing, shapes) into the Compose theme and rebuild each screen
  natively against the real engine. Pixel-faithful, but native.
- **One rule when prompting any design tool:** demand *Android / Material 3 / dark theme*, and
  demand the *offline & state variants* of each screen — those states are our product's soul and
  every tool forgets them unless told.

### 11.3 The holistic design prompt (copy-paste when the vision is approved)

```
Design a complete dark-themed ANDROID mobile app UI (Material 3 design system, NOT iOS, NOT a
website) for "JARVIS" — a voice-first AI companion that lives in smart glasses (camera + mic +
speaker, no display). The phone app is the companion console: calm, ambient, glanceable. The user
mostly talks to the glasses; the app shows state, memories, and settings.

BRAND & MOOD: confident, calm, sci-fi-but-warm (Iron-Man's JARVIS energy, not cyberpunk noise).
Base ink #0B0F14, dark navy surfaces, ONE accent: electric cyan #3DDCFF used sparingly (glows,
active states, primary actions). Amber #FFB454 is reserved ONLY for "offline / saved locally /
pending sync" semantics. Red only for true errors. Generous spacing, large rounded corners (M3
extra-large shapes), subtle glass/blur on elevated sheets. Display font: Space Grotesk (or similar
geometric); body: Inter; live transcripts in a mono font.

THE HERO ELEMENT: an animated "presence orb" representing Jarvis on the Home screen — design 5
visual states: idle (slow breathing cyan glow), listening (ripples), thinking (orbiting particles),
speaking (waveform pulse), off-grid (amber ember, calm, still alive). Show all 5 as variants.

SCREENS (design every one, phone portrait, plus the listed states):
1. HOME / LIVE — presence orb center; two status chips beneath it: "Glasses · Connected" and
   "Cloud · Full" (variants: Lean, Off-grid in amber; Glasses · Searching…); a large round
   push-to-talk mic button WITH a compact "type instead" text-input affordance beside/below it
   (tapping expands a chat input bar — the keyboard path must feel first-class, not hidden);
   the last Q&A exchange as a two-bubble transcript card (every answer is always shown as text,
   speech is optional); a tiny mic-source chip ("Mic: glasses / phone / keyboard"); small "Sync
   from glasses" pill showing "3 new captures". States: online; off-grid (amber accents, copy
   says "Saving everything on your phone — will sync later"); glasses disconnected; and a
   "voice unavailable — type to Jarvis" state where the text input becomes the hero.
2. TIMELINE — natural-language search field at top ("the bookshop with the green door…");
   reverse-chronological memory cards grouped by day: photo memory (thumbnail + AI description),
   voice note (waveform + transcript snippet), Q&A exchange, place memory (mini-map chip). Unsynced
   cards show a small amber "on phone" tick. Include an empty state ("Your memory river starts
   here") and a search-results state.
3. MEMORY DETAIL — full-bleed photo hero, AI description, timestamp + location chip (mini map),
   tags, "related memories" horizontal row, actions: Ask about this, Share, Delete. Variant for a
   voice-note memory with an audio player.
4. GALLERY — photo grid of visual memories, month section headers, multi-select mode.
5. CAPTURES / SYNC — "On your glasses" vs "Imported" sections, per-file progress bars during a
   Wi-Fi Direct sync, auto-sync toggle, storage-used meter. Show an in-progress sync state.
6. GLASSES DEVICE PAGE — illustrated glasses with battery %, connection status, "buttons map"
   showing what each physical button does (photo / AI ask / voice note), connection-doctor card
   with a "Fix connection" flow entry.
7. SETTINGS — account; "Offline Pack" download card (size, progress, downloaded state) explaining
   the on-device AI; hands-free presets (Always / While charging / Off); privacy section with
   "Export all my data" and a destructive "Delete everything"; about.
8. ONBOARDING / PAIRING WIZARD — 6 steps: welcome (orb intro) → sign in (Google + email code) →
   permissions explainer (mic, nearby devices — friendly copy on WHY) → finding glasses (radar
   animation) → test capture & sound (checklist that ticks live) → "Say 'Jarvis'" finale. Design
   step 4 also in a failure state with a help path.

GLOBAL: bottom navigation with 4 items (Live, Timeline, Gallery, Settings) using M3 nav bar with
the cyan active indicator; status chips and amber offline semantics consistent everywhere; light
haptic/motion notes welcome. Deliver cohesive components (cards, chips, buttons, search field) as
a small design system page too. Everything must feel like ONE product: calm, dark, alive.
```

### 11.4 Integration contract (so the design lands cleanly back in the engine)

When the design comes back, I will: extract a `JarvisTheme` (M3 `ColorScheme`, `Typography`,
`Shapes` from the tokens) → rebuild screens as Compose composables wired to the existing
ViewModels (`HomeViewModel.syncGlasses()`, `lookAndAsk()`, talk loop, tier state) → keep the orb as
a custom `Canvas` composable with the 5 animated states driven by the real assistant state machine.
Nothing in the engine needs to change for the skin to land — that's why the engine phases (A–D)
come first.

---

## 12. Sequence summary (what I'd literally do next)

1. **A** — finish image persistence (days; fixes a live bug).
2. **B** — button recon + reactions + foreground service (the product's soul).
3. **C** — offline-first core (Room+outbox, local embeddings, tier governor, Jarvis Lite pack).
   *UI design runs in parallel: approve vision → run the prompt in Stitch → iterate taste.*
4. **D** — latency (VAD first, then streaming).
5. **UI integration** — tokens → Compose theme → screens over the stable engine.
6. **E → F → G** — auth/onboarding/cloud → hardening/privacy → Play beta.
7. **H** — ops, cost guardrails, staged rollout.
