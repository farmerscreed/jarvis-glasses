# JARVIS/ECHO — Full Build Plan (AIMB-G2 AI Companion)

*Approved 2026-06-10. Derived from Sessions 01–04 recon. Read `00_HANDOFF_START_HERE.md` first for current state; this is the build plan.*

## Context

We are building an Android-first personal AI companion ("voice in your ear that sees through your eyes") for the **AIMB-G2 smart glasses**. The glasses are dumb sensors + a speaker; all intelligence runs on the phone + cloud. The differentiator is a **shared Personal Memory Index** (Supabase `pgvector`) that every feature reads/writes — one mind, many doors.

All feasibility recon is **done and documented** in this folder (`00_HANDOFF_START_HERE.md`, `Device_Recon_Record.md` Sessions 01–04, `Methodology_Reproducible_Tests.md`). Established on real hardware:
- **Audio** (mic/speaker) works as a standard Bluetooth headset (HFP + A2DP).
- **Camera is fully reverse-engineered**: trigger = BLE write `BC 41 03 00 10 50 02 01 01` to handle `0x008E`; transport = **Wi-Fi Direct**; protocol = glasses run an **HTTP server on port 80** (`GET http://<ip>/files/media.config`, then `/files/<file>`). BLE control runs through the vendor SDK `com.oudmon.ble` (`LargeDataHandler.glassesControl(byte[])`).
- No app code exists yet; this is greenfield. **Decision locked:** the "brain" is a **cloud LLM (Claude)**, not a self-hosted Hermes model (kept as a future swap behind an interface).

This plan turns the research into a phased, buildable implementation.

---

## 1. Stack decisions (locked defaults — adjustable)

| Concern | Choice | Why |
|---|---|---|
| Platform | **Android, Kotlin** | Decided in brief; test device is Pixel 8 / Android 16 |
| UI | **Jetpack Compose + MVVM**, Coroutines/Flow, Hilt DI | Modern standard, testable |
| Min/Target SDK | min **29**, target latest | WifiP2p + BLE behave well; matches device |
| BLE | **Reimplement** the needed oudmon commands on `BluetoothGatt` | Vendor SDK is closed/obfuscated; we already have the byte formats. (Fallback: extract oudmon AAR.) |
| Wi-Fi transfer | `WifiP2pManager` + **OkHttp** | Mirror the stock app's proven path |
| Backend / memory | **Supabase** (Postgres + `pgvector`, Storage, Auth, Edge Functions) | Brief's keystone; ownable; MCP/skill available here |
| Brain (LLM + vision) | **Claude (multimodal)** behind a `LlmClient` interface | Best reasoning for a "second brain"; latest model |
| STT | **Deepgram** (streaming) — interface-abstracted | Low-latency; swap to Whisper/Azure trivially |
| TTS | **ElevenLabs/Cartesia** (quality) or Azure (cheap) — abstracted | Voice is the product; keep swappable |
| Embeddings | **Voyage AI** (Anthropic-recommended) or OpenAI `text-embedding-3` | For `pgvector` |
| Secrets | **All provider keys live in Supabase Edge Functions**, never in the app | Security |

**Architecture rule:** every model (LLM/STT/TTS/embeddings) sits behind an interface and is called via a Supabase Edge Function — so providers swap without touching the app, and keys stay server-side.

---

## 2. Target architecture — the 7 layers

```
GLASSES (mic/speaker + camera)            ── Bluetooth (audio+control)  /  Wi-Fi Direct (images)
   │
ANDROID APP (orchestrator)
   1 Activation   button / wake word
   2 Ears         BT-SCO mic capture → VAD → STT
   3 Brain        Claude (tool-calling)         ← via Edge Function
   4 Skills       memory.recall, camera.capture, reminder.set, translate, web.search  (function tools)
   5 Memory       Supabase pgvector (retrieve-before-answer, write-after)
   6 Mouth        TTS → A2DP playback in-ear
   7 Orchestrator the capture→retrieve→think→act→speak→remember loop
   │
CLOUD (Supabase): Postgres+pgvector · Storage · Auth · Edge Functions (chat/ingest/recall/transcribe/tts/vision)
```

The orchestrator (layer 7) and memory (layer 5) are **ours to own** — that's the differentiation. Layers 1/2/6 borrow best-in-class components; layer 3 is a swappable cloud model.

---

## 3. Android module structure (multi-module Gradle)

| Module | Responsibility | Key new classes |
|---|---|---|
| `:app` | Compose UI, navigation, app shell, onboarding/auth screens | `MainActivity`, feature screens |
| `:core` | DI, models, Result/error types, utils, logging | `di/`, `model/`, `Dispatchers` |
| `:device:ble` | GATT connect to glasses; **oudmon command framing**; camera trigger; battery/time/device-info | `GlassesBleClient`, `OudmonCommand`, `BleCommandCodec` |
| `:device:audio` | BT-SCO mic capture, A2DP playback, VAD | `BtAudioManager`, `MicStream`, `Vad` |
| `:device:wifi` | Wi-Fi Direct group + HTTP media pull from glasses | `GlassesP2pManager`, `MediaTransferClient` |
| `:ai` | Interfaces + Edge-Function clients for LLM/STT/TTS/embeddings | `LlmClient`, `SttClient`, `TtsClient`, `EmbeddingClient` |
| `:memory` | Supabase client, ingest + RAG recall, media upload | `MemoryRepository`, `RecallQuery` |
| `:assistant` | The orchestrator loop + tool registry (function-calling) | `AssistantOrchestrator`, `ToolRegistry`, tool impls |
| `:feature:*` | Meeting, LookAsk, ReadIt, Translate, SecondBrain | per-feature VM + screen |

---

## 4. Backend (Supabase) design

**Schema (core):**
```
profiles(user_id, ...)                         -- Supabase Auth
memories(
  id uuid pk, user_id uuid, created_at,
  type text,                  -- voice_note | meeting | photo | qa | ocr | journal
  text text,                  -- transcript / answer / OCR text
  embedding vector(N),        -- N per chosen model
  media_path text,            -- Storage ref (image/audio), nullable
  location geography,         -- nullable
  tags text[], metadata jsonb
)
```
- **pgvector** HNSW index on `embedding`; RLS so users only see their rows.
- **RPC** `match_memories(query_embedding, match_count, threshold, filter_type, user)` → ranked memories.
- **Storage** buckets: `media` (images), `audio` (clips), private + signed URLs.
- **Edge Functions** (hold all provider keys):
  - `recall` — embed query → `match_memories` → return.
  - `ingest` — embed text → insert memory (+ optional media upload).
  - `chat` — RAG: recall → Claude (with tool defs) → stream answer; persists the Q&A as a memory.
  - `transcribe` — STT proxy (Deepgram).
  - `tts` — TTS proxy (returns audio stream).
  - `vision` — multimodal Claude on an image + prompt.

---

## 5. Reusing the decompiled reference (mirror map)

We **mirror, not copy**, these decompiled files (`…\decompiled\sources\com\aitowe\aitoglasses\…`) to reimplement device protocols correctly:

| Build target | Mirror this reference | What to extract |
|---|---|---|
| `:device:wifi` P2P lifecycle | `wifi/p2p/WifiP2pManagerSingleton.java` | discover→connect→timeout/retry logic; BLE `glassesControl({2,1,15})` to start/reset P2P |
| `:device:wifi` HTTP pull | `home/PictureFragment.java` (`downloadMediaConfig`, ~L283–355) | URL shape `http://<ip>/files/{media.config,<file>}`; constants `media.config`, `log.list`; IP-from-BLE callback (~L1573) |
| `:device:ble` commands | `com/oudmon/ble/base/communication/LargeDataHandler.java` + callers | the `glassesControl` byte commands + `BaseResponse`/`GlassModelControlResponse` fields |
| camera trigger | our snoop capture (`btsnoop_hci.log`) + `parse_btsnoop.ps1` | confirmed bytes `BC 41 03 00 10 50 02 01 01` @ handle `0x008E` |
| OTA (later) | `ota/OTAActivity.java` | phone-as-`ServerSocket` reverse-transfer pattern |

> One open item to resolve during Phase 0D: the **UUID of camera handle `0x008E`** (discovery was cached). Resolve via nRF Connect or WinRT `AttributeHandle` (Methodology §A), or by reading the oudmon SDK's UUID constants in `decompiled/`.

---

## 6. Phased implementation

### Phase 0 — Foundation (4 parallel tracks)
- **0A Backend:** Supabase project; schema + `pgvector` + RLS + `match_memories` RPC; Storage buckets; Auth; Edge Function skeletons (`recall`, `ingest`, `chat`) wired to one LLM + one embedding provider.
- **0B App skeleton:** multi-module Gradle, Hilt, Compose nav, Supabase Auth login, settings.
- **0C Device-Audio:** connect glasses as BT headset; prove **mic capture (SCO)** + **TTS playback (A2DP)** from our app (`:device:audio`).
- **0D Device-BLE:** GATT connect; implement oudmon framing for device-info, battery, set-time, **camera trigger**, **P2P-start**; validate each against captured snoop bytes.
- **Exit criteria:** can log in; can speak into glasses and hear audio out from our app; can send a BLE command the glasses obey; Supabase stores+recalls a hand-inserted memory.

### Phase 1 — First working loop (audio-only): **Meeting Capture**
- `:assistant` orchestrator (push-to-talk first): capture audio → `transcribe` → Claude summarize/extract action-items → `ingest` to memory → **recall by voice** via `chat`.
- Proves layers 2-3-5-6-7 with zero camera dependency.
- **Exit criteria:** record a meeting, get a spoken summary, later ask "what did we decide about X?" and get a correct, memory-grounded answer in-ear.

### Phase 2 — Vision (camera client)
- `:device:wifi`: BLE start cmd → Wi-Fi Direct group → read glasses IP (from BLE) → `GET /files/media.config` → parse manifest → `GET` each file → store + upload to Storage + `ingest`.
- On-demand capture via the BLE `BC` trigger.
- Build **Look & Ask** (capture → `vision` → speak), **Read-it-to-me** (OCR), and the **visual half of Second-Brain**.
- **Exit criteria:** "Hey Jarvis, what am I looking at?" → photo captured on demand → spoken answer; photo + voice tag stored and recallable.

### Phase 3 — Polish & expand
- Wake word (openWakeWord/Porcupine) replacing push-to-talk; Translation (sign/menu OCR + live); proactive nudges; life-logging; accessibility suite. Pick from brief §4 "additional possibilities".

---

## 7. Cross-cutting concerns

- **Privacy (gate before any related feature):** consensual-only; on-device wake word; user owns data in their Supabase; encryption at rest/in transit; **director sign-off required** for face-recognition or always-on recording (brief §8).
- **Model abstraction:** all providers behind interfaces + Edge Functions → Claude↔others and cloud↔self-host (Hermes) are config swaps, not rewrites.
- **Latency budget:** target < ~2s mic-stop→first-audio-out; stream STT and TTS; pre-warm.
- **P2P reliability:** mirror the stock app's discover/connect retry + timeout (it is finicky by nature).
- **Dev constraint:** glasses Bluetooth is **single-point** — PC (WinRT enumeration) **or** phone (app), not both.
- **Battery/thermal:** P2P + camera are power-hungry; bring up on demand, tear down after (as stock app does).

---

## 8. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Reimplementing oudmon BLE commands is incomplete | Validate every command vs captured snoop bytes; fallback = extract oudmon AAR from `decompiled/` |
| Wi-Fi Direct flakiness | Copy stock retry/timeout logic; expose manual retry; cache last glasses IP |
| STT/TTS latency & cost | Streaming providers; cache; allow cheaper provider via interface |
| Camera handle `0x008E` UUID unknown | Resolve in 0D (nRF Connect / WinRT / SDK constants) |
| Wake-word quality | Start push-to-talk / button; add wake word in Phase 3 |
| Single-point BT slows dev | Time-share; script the PC↔phone handoff (Methodology §A/§B) |

---

## 9. Verification & testing strategy

- **Unit:** codecs (`BleCommandCodec`), RAG query building, manifest parsing.
- **Instrumented (Pixel 8):** BLE connect + command ACK; SCO capture; A2DP playback; P2P connect + one HTTP GET.
- **Device-in-the-loop:** reuse `Methodology_Reproducible_Tests.md` (adb snoop to confirm our app emits the same trigger bytes; verify a `/files/` GET succeeds).
- **Backend:** Supabase local/CI — schema migration, `match_memories` correctness, Edge Function happy-path via the Supabase MCP/skill.
- **End-to-end acceptance** (per phase exit criteria above): meeting→recall (P1); look→ask→answer (P2).

---

## 10. Open decisions (recommended defaults baked in — flag to change)

1. **LLM:** Claude multimodal *(recommended)*.
2. **STT / TTS:** Deepgram / ElevenLabs *(recommended; Azure to mirror OEM if cost-driven)*.
3. **Embeddings:** Voyage AI *(recommended)*.
4. **V1 feature:** Meeting Capture *(per brief — recommended)*.
5. **BLE:** reimplement *(recommended)* vs extract oudmon AAR.
6. **Mic for V1:** glasses SCO *(validates hardware early)* with phone-mic fallback.

---

## 11. Immediate next actions (first sprint)

1. ✅ This plan saved as `01_IMPLEMENTATION_PLAN.md`.
2. Stand up **Supabase**: project + `memories` schema + `pgvector` + `match_memories` RPC + Storage + Auth (use the Supabase skill/MCP).
3. Scaffold the **Android multi-module** project (`:app`, `:core`, `:ai`, `:memory`, `:assistant`, `:device:*`).
4. Land **0C audio loopback** (speak→hear via our app) and **0D BLE connect + camera-trigger validation** in parallel.
5. Resolve the camera-endpoint `0x008E` UUID.

> First code milestone = Phase 0 exit criteria: login + audio in/out through our app + one obeyed BLE command + a round-tripped Supabase memory.
