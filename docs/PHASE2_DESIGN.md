# Phase 2 — Meetings: full build design (2026-10-12)

*Scope from `PRODUCT_PLAN.md` Phase 2: **meeting capture**, **recordings library + smart storage**, **cheaper
model routing**. Decisions already made by Law: in-person meetings only (no call recording), voice-activated
start, recordings need a full design, running cost as low as possible (China-hosted models OK if reliable).
Mockups: row 3 of https://claude.ai/artifact/X1J3XY65QGdcwvntpiR5Ee (sources in `docs/design/redesign-2026-10/`
`Meeting*.dc.html`, `Recordings.dc.html`). UI follows `docs/UI_REDESIGN.md` / `ui/components/Neo.kt`.*

---

## 1. What Law gets (the user story)

> At a site meeting Law says **"Jarvis, record this meeting"** (or taps **Meeting** on Home). JARVIS says
> "Recording — remember to tell everyone." He puts the phone on the table. During the meeting he can say
> "Jarvis, mark that" to bookmark a moment and "Jarvis, am I still recording?" ("Yes — 23 minutes.").
> He says **"Jarvis, stop recording."** A few minutes later a notification arrives: *"Site meeting with
> Mr Ade — summary ready: 3 action items, 2 decisions."* The summary, action items (with owners and due
> dates), numbers quoted, decisions, full speaker-labelled transcript and the audio are in **Memories →
> Recordings**. Later: *"Jarvis, what did Mr Ade say about delivery?"* answers from the meeting.

Works with no signal (records on the phone, processes when back online). Audio is kept 90 days unless
starred; text is kept forever.

## 2. Architecture

```
 Phone (Kotlin)                                        Cloud (Supabase jarvis-prod)
 ─────────────────────────────────────────            ─────────────────────────────────────────────
 MeetingRecorderService (FGS: microphone)              Storage bucket  recordings/ (private, RLS)
   AudioRecord 16 kHz mono → AAC 24 kbps (.m4a)          <uid>/<recording_id>/<seq>.m4a
   5-minute CHUNKS, written + closed one by one        Tables  recordings · recording_chunks ·
   bookmarks (t, label) · level meter                          recording_segments · llm_calls ·
 RecordingStore (Room: local_recordings/chunks)               model_routes
   outbox → upload each chunk as soon as it's closed   Edge Function  meeting
   (WorkManager, survives app kill / offline)            op upload-done → transcribe chunk (Gemini)
 UI: Meeting start · Recording · Recordings library      op finish      → assemble + summarise (router)
     · Meeting detail (Summary / Transcript / audio)     op rename-speaker · star · delete
 Voice: record / stop / mark / status / ask            Edge Function  retention (daily, pg_cron)
 Glasses (later, M8): BACK-hold .opus → same pipeline  _shared/llm.ts  model router (OpenRouter + Anthropic
                                                        + Gemini), logs every call to llm_calls
                                                       sendPush() when the summary is ready
```

Why chunks: a 1-hour meeting is one ~11 MB file — uploading it whole on site data is fragile, and one
model call over an hour of audio is slow and all-or-nothing. 5-minute chunks (~0.9 MB each) upload as the
meeting goes, transcribe in parallel in the cloud, and a failed chunk is retried alone. The summary step
only waits for the last chunk, so the summary lands minutes after "stop", not an hour after.

Why the phone mic (default): it sits in the middle of the table and hears everyone; the glasses' mic is
tuned to the wearer and their recordings are firmware-controlled (no known voice-start command yet). Glasses
recordings join the same pipeline in M8.

## 3. Data model (one migration per milestone)

```sql
-- recordings: one per meeting / long voice recording
create table public.recordings (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references auth.users(id) on delete cascade,
  client_id     text unique,                 -- phone-generated, idempotent uploads
  kind          text not null default 'meeting',     -- meeting | voice_note | glasses_audio
  source        text not null default 'phone',       -- phone | glasses
  title         text not null default '',
  started_at    timestamptz not null,
  ended_at      timestamptz,
  duration_s    int not null default 0,
  status        text not null default 'recording',
     -- recording | uploading | transcribing | summarising | ready | failed
  chunks_total  int,                         -- known once stopped
  bookmarks     jsonb not null default '[]', -- [{t_s, label}]
  speakers      jsonb not null default '{}', -- {"Speaker 1":"Law","Speaker 2":"Mr Ade"}
  summary       jsonb,                       -- see §5 schema
  memory_id     uuid,                        -- the 'meeting' memory made from the summary
  audio_bytes   bigint not null default 0,
  starred       boolean not null default false,
  audio_expires_at timestamptz,              -- now()+90 days at ready; null when starred
  audio_deleted_at timestamptz,
  error         text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create table public.recording_chunks (
  recording_id uuid references public.recordings(id) on delete cascade,
  seq int, path text not null, bytes int not null, sha256 text not null, duration_s int not null,
  status text not null default 'uploaded',   -- uploaded | transcribing | done | failed
  attempts int not null default 0, error text,
  primary key (recording_id, seq)
);
create table public.recording_segments (     -- the transcript, speaker-labelled
  recording_id uuid references public.recordings(id) on delete cascade,
  seq int, idx int, t_start_s real, t_end_s real, speaker text, text text,
  primary key (recording_id, seq, idx)
);
-- RLS: owner-only (to authenticated, (select auth.uid()) = user_id) on recordings; chunks/segments via
-- exists(select 1 from recordings r where r.id = recording_id and r.user_id = (select auth.uid())).
-- Storage bucket `recordings` private; object path starts with the user id (same pattern as media/audio).
```
- `memories.type` already allows `'meeting'` (since June). The meeting memory text = title + "In short" +
  decisions + action items + numbers, so ordinary recall/chat finds it; `metadata.recording_id` links back.
- Local (Room): `local_recordings`, `local_chunks` mirror the above (status, path, uploaded flag) — the phone
  is the source of truth until each chunk is confirmed uploaded (size + sha256 match).

## 4. Phone: recording engine (M1)

- **`MeetingRecorderService`**: foreground service type `microphone` (like `TranslateService`), ongoing
  notification "Recording · 23:41" with **Mark** and **Stop** actions, partial wake lock. `AudioRecord`
  (16 kHz mono, `VOICE_RECOGNITION` source, built-in mic preferred) → `MediaCodec` AAC-LC 24 kbps →
  `MediaMuxer` .m4a; a new chunk file every 5 min (at a quiet moment ±10 s when possible, so words aren't cut).
  ≈ 11 MB per hour.
- **Never lose audio:** chunks are closed and fsync'd as they finish; on crash/kill the service restarts
  (`START_STICKY`) and opens a new chunk; on low storage (<200 MB) JARVIS says so and keeps going; on low
  battery (<10 %) it warns once.
- **Earcons + voice:** start/stop tones in the glasses; "Recording — remember to tell everyone."; "Stopped.
  I'll let you know when the summary's ready."
- **Voice commands** (handled by the existing voice loop, not the recorder): *record this meeting / start
  recording* → consent line + start; *stop recording* → stop; *mark that / mark this* → bookmark
  (optional label: "mark that — price agreed"); *am I still recording?* → "Yes — 23 minutes."; while
  recording, the wake word still works for other questions (the recorder keeps recording; JARVIS's own
  speech is marked so it can be ignored in the transcript).
- **Consent:** first meeting ever → a one-time explanation; every meeting → the spoken reminder and the
  amber card on the start screen. (Nigeria Data Protection Act: telling participants is the user's job; the
  app reminds every time.)
- **Mic sharing:** recording and live translation can't share the mic — starting one while the other runs
  asks "Stop translating and start recording?".

## 5. Cloud: transcription + summary (M2–M4)

**`meeting` Edge Function** (user-JWT; storage + tables via service role, always filtered by user):
- `op: "chunk-uploaded" {recording_id, seq}` → marks the chunk; kicks transcription (below) in the same
  invocation (≈ 20–40 s for a 5-min chunk) — fits the function time limit; long work never blocks the phone.
- **Transcribe a chunk:** download from storage → Gemini (current STT model, `thinkingBudget: 0`) with the
  accent-primed prompt + `STT_VOCAB` + known speaker names → **JSON** `[{t_start, t_end, speaker, text}]`
  ("Speaker 1/2…" consistent within the chunk; the speaker map is reconciled at finish). Store segments;
  chunk `done`. Retries ×3 with backoff; then `failed` (the recording still finishes; a gap is shown).
- `op: "finish" {recording_id, chunks_total, duration_s, bookmarks}` → when every chunk is done (or failed):
  1. **Speaker reconcile** (cheap model): map chunk-local speaker labels to stable ones using overlap
     context; guess names from the talk ("thank you, Mr Ade") → `speakers` (user can rename).
  2. **Summary** via the router (job `meeting_summary`), structured output:
     `{title, in_short, decisions[], action_items[{task, owner, due}], numbers[{label, value}],
       people[], follow_ups[], open_questions[]}` — rules: only what was said, owners/dates only if said,
     money in naira as spoken, `[unclear]` instead of guesses.
  3. Save `summary`, make the **meeting memory** (embedded → recall), set `ready`, `audio_expires_at`.
  4. **Push:** "‹title› — summary ready: N action items, M decisions." (data `{kind:"meeting", id}` opens it).
- Long meetings (> ~2 h): summarise per 30-min block first, then summarise the block summaries.
- Cost control: transcription on Gemini Flash (cheap per audio minute); summary on the router's cheap tier
  with Claude as backup; every call logged in `llm_calls` (tokens, $ estimate) — Settings shows the month.

## 6. Recordings library + smart storage (M5–M6)

- **Memories → Recordings** filter (mockup `Recordings.dc.html`): storage strip ("Audio kept 90 days · N
  starred · 212 MB"), cards with live status (Uploading 3/10 · Transcribing · Summarising… · Ready ·
  Audio expired, text kept), action/decision counts, star.
- **Meeting detail** (mockups `MeetingSummary`, `MeetingTranscript`): Summary / Transcript tabs; action items
  are tick-able (state saved); numbers as tiles; transcript lines with time + speaker (tap → play from
  there; tap a speaker name → rename everywhere); bookmarks shown inline and on the scrubber; player with
  −15/+15 s and 1×/1.5×/2×; **Ask** (Ask JARVIS pinned to this meeting); Share (summary text; PDF later);
  Star; Delete (audio only, or everything).
- **Storage policy** (PRODUCT_PLAN):
  1. **Text forever** — transcript, summary, action items, photo descriptions (cloud).
  2. **Audio 90 days** (setting: 30 / 90 / 365 / forever), then deleted unless starred. Daily
     **`retention`** Edge Function (pg_cron → pg_net) deletes expired objects via the Storage API, sets
     `audio_deleted_at`, keeps everything else. The phone deletes its local chunks once uploaded + verified
     and > 7 days old (keeps the newest week for offline playback).
  3. **Photos** — full image in the cloud, the phone keeps a 512-px preview after upload is confirmed.
  4. **Video** — stays on the phone; only key frames + a description go up (Phase 3 video work).
  5. **Starred → Google Drive** (`JARVIS/Recordings/…`) instead of paid storage — needs the Google
     connection (+ `drive.file` scope) → M6b, after Law's Google setup.
  6. **Glasses safety** — the glasses delete a file only after the phone has all of it: verify byte length
     (HTTP Content-Length / file size) before treating a pull as done; investigate what makes the glasses
     clear storage (they did it by themselves ~30 s after a pull on 10-11) in the test session (M8).

## 7. Cheaper model routing (M7)

- **One switchboard:** `_shared/llm.ts` → `llm(job, {system, messages, schema?, image?})`. Routes per job
  from the `model_routes` table (editable without an app update): `[{provider, model, max_tokens}]` primary
  + fallbacks. Providers: **OpenRouter** (one key → DeepSeek, Kimi, GLM, Qwen, Grok…; OpenAI-compatible),
  **Anthropic** (Claude), **Gemini**. *Recommend OpenRouter over LiteLLM: no server to run (Prime's VPS is
  off).*
- **Jobs and starting routes** (to be confirmed by the eval below — check current prices first):

  | Job | Start with | Backup |
  |---|---|---|
  | `stt` (speech, meeting chunks) | Gemini Flash (current) | — |
  | `meeting_summary`, `translate_summary` | cheap tier (DeepSeek / Kimi / GLM via OpenRouter) | Claude |
  | `memory_tagging`, `distill`, `person_parse`, `calendar_parse` | cheap tier / Haiku 5.5 | Claude |
  | `chat` (everyday) | cheap tier, escalate hard questions to Claude | Claude |
  | `vision`, `look_research` | Claude / Gemini | the other |
  | `email_draft` (anything in Law's name) | Claude | — |
- **Logging:** `llm_calls(job, provider, model, tokens_in, tokens_out, usd_est, ms, ok, error, created_at)`;
  Settings → Advanced → **AI models & cost** shows this month's spend per job.
- **Eval before switching:** a script runs 10 real meeting transcripts (Law's, with consent) through each
  candidate; Law compares the summaries side by side (accuracy of numbers/owners/dates is the bar).
  Migrate existing functions to the router one job at a time.

## 8. Screens & navigation changes

- Home grid: **Find glasses → Meeting** (Find glasses stays in the status line, voice and Settings) — so the
  six tiles are Look · Translate · Ask · Research · Remember · Meeting.
- New: `MeetingStartScreen`, `MeetingLiveScreen` (also reachable from the ongoing notification),
  `RecordingDetailScreen` (Summary/Transcript + player). Memories gains the **Recordings** filter.
- A slim **"Recording · 23:41"** bar on every tab while recording (like the Translating bar).
- Settings → Advanced: **Keep audio for** (30/90/365/forever), **AI models & cost**.

## 9. Build plan (milestones, each ends on the phone with Law)

| # | Milestone | Done when (acceptance) |
|---|---|---|
| **M1** | Recording engine + voice commands + start/live screens | 60-min phone recording with screen off: no gaps, 12 chunks, ≈11 MB; start/stop/mark/status by voice; notification Stop works; survives app kill |
| **M2** | Storage + upload (bucket, tables, Room mirror, outbox) | chunks upload during the meeting; airplane mode mid-meeting → uploads resume; sizes + sha256 match |
| **M3** | Chunk transcription (`meeting` fn) | 5-min chunk → speaker-labelled segments in < 60 s; accuracy acceptable on Law's accent (STT_VOCAB set) |
| **M4** | Finish: speakers, summary, memory, push | summary within ~3 min of stop; numbers/owners correct on 3 real meetings; "what did Mr Ade say about…" answered |
| **M5** | Library + detail UI | Recordings filter, detail, play-from-line, rename speaker, tick actions, star, delete |
| **M6** | Retention + phone cleanup | expired audio deleted by the daily job, text kept; starred kept; local cleanup |
| **M6b** | Starred → Google Drive | after Google setup; starred file appears in Drive |
| **M7** | Model router + eval + cost view | all jobs through `llm()`; eval run; Law picks models; monthly cost visible |
| **M8** | Glasses test session + glasses recordings in the pipeline | longest recording/video, size, sync time, battery, heat measured; BACK-hold .opus > 2 min becomes a recording with summary; deletion-trigger understood |

### Status (2026-10-14) — details in `SESSION_LOG_2026-10-11.md` §14–15 and `SESSION_LOG_2026-10-14.md`

| # | Status | Notes |
|---|---|---|
| M1 | ✅ built, device-tested | 430 s screen-locked → 2 valid chunks. AAC **32 kbps** (not 24), `MediaRecorder` + `setNextOutputFile` (not AudioRecord/MediaCodec). |
| M2 | ✅ built, device-tested | 26-min meeting, 6/6 chunks, bytes + sha256 match. Progress in `recordings/<id>/upload.json` (not Room). Expired-token fix. |
| M3 | ✅ built, tested on real chunks | 13–22 s per 5-min chunk; "Media" label for TV/music; one invocation per chunk. |
| M4 | ✅ built, tested on the phone | Real conversations summarised (2026-10-15); numbers exact; 4 bugs fixed in the test (see 10-14 log §12–13). |
| M5 | ✅ built, tested on the phone (2026-10-15) | Library + detail as in the mockups; Ask about this meeting; share; delete audio-only/everything. No transcript auto-scroll / PDF yet. |
| M6 | ✅ built, tested (keep setting, delete audio-only/everything) | `meeting-retention` daily; keep 30/90/365/forever; phone frees audio 7 days after backup. |
| M6b | 🟡 built, **inert** | Needs Law's Google setup incl. the Drive API; scope `drive.file`. |
| M7 | 🟡 built | Switchboard + `llm_calls` + cost view + eval script. OpenRouter path untested (no key); routes still Claude-first. |
| M8 | ✅ built 2026-10-16 (needs the glasses test) | Glasses' own recorder: `02 01 08` start/stop (`GlassesBleManager.toggleAudioRecording`, reply `… 02 01 08 err work`), length `02 06 00 lo hi` set to 7200 s (stock choices 1800/3600/7200). Record → Meeting → **Glasses mic**, voice "record this meeting on the glasses", "stop recording", red bar. The synced `.opus` → `VideoIngest.ingestAudio` (Media3 → AAC `NNN.m4a` ≤ 5 min, built off to the side) → normal meeting pipeline (kind meeting, source glasses). Rule: the next glasses audio after an app-started glasses meeting, or any glasses audio ≥ 2 min, is a meeting (`VideoPrefs.takePending`); shorter = voice note as before. Unverified until the test: whether `02 01 08` again stops it, the reply bytes, opus decoding on the Pixel. |

Deviations from the design above: status flow gained `uploaded` and `transcribed`; long work runs as one job per
invocation of `meeting` (self-kick with a shared secret) plus a 5-minute pg_cron **sweep** that restarts anything
stuck; retention is an op inside `meeting` (creating new functions fails on this project), not a separate
`retention` function.

Order rationale: M1–M4 give Law a working "record → summary" loop fast; M5 makes it browsable; M6/M7 cut
cost; M8 depends on a hands-on session with the glasses.

## 10. Risks & how they're handled
- **Accent / names** → `STT_VOCAB` + speaker names fed into each chunk's prompt; renaming speakers improves
  later chunks of the same meeting.
- **Noisy sites** → phone on the table is better than the glasses mic; `NoiseSuppressor` experiment if needed.
- **Function time limits** → per-chunk work (~30 s) and per-block summaries keep every call short.
- **Battery** → AAC encoding at 24 kbps is light; measured in M1 (target < 6 %/hour).
- **Privacy** → private bucket + RLS, consent reminder, delete anytime, retention by default.
- **Cost drift** → `llm_calls` log + monthly view; router lets Law cap expensive jobs.

## 11. What Law needs to do
1. **OpenRouter account + API key** (for M7) — put it in `supabase/functions/.env` as `OPENROUTER_API_KEY`;
   I upload it as a secret without showing it. Add a little credit.
2. **Set `STT_VOCAB`** (names, sites, suppliers) — matters a lot for meeting transcripts.
3. **Google setup** (already listed) — needed only for M6b (Drive archive).
4. **A 30-minute glasses test session** with me (M8): long recording, long video, battery/heat, and
   capturing the stock app's "start recording/video" Bluetooth command.
5. **2–3 real meetings** to test M3/M4 (with the other people's agreement).
