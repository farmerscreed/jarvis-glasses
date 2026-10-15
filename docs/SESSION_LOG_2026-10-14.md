# Session log — 2026-10-12 → 14 (Phase 2: meetings, M2–M7 + M6b)

*Continues `SESSION_LOG_2026-10-11.md` (§14 = M1 recording engine, §15 = M2 upload). Design and milestone
table: `docs/PHASE2_DESIGN.md`. Test steps for Law: `docs/TEST_PLAN_PHASE2.md`.*

## 1. What exists now (end to end)

```
Phone (Pixel, prod flavour)                         Cloud (Supabase jarvis-prod, function `meeting`)
──────────────────────────────────────              ──────────────────────────────────────────────────────
MeetingRecorder  phone mic → AAC 32 kbps 16 kHz      recordings bucket  <uid>/<rec-id>/NNN.m4a (private)
  ~5-min chunks NNN.m4a + meta.json                  op chunk   → download, check bytes + sha256 → row in
MeetingUploader / MeetingUploadWorker                             recording_chunks → kick work-chunk
  each closed chunk → storage → op chunk             work-chunk → Gemini → recording_segments (lines)
  after Stop → op finish; upload.json = progress     op finish  → kicks pending chunks; when all done →
  7 days after backup: deletes its audio copy                     "transcribed" → kick work-summary
  delete → op delete (queued in .deleted offline)    work-summary → llm router (job meeting_summary) →
MeetingLibrary  list/get/audio-urls/star/title/                   summary + `meeting` memory + push
  speaker/tick/delete-audio/ask/settings/costs       sweep (pg_cron 5 min) → restarts stuck/failed jobs
RecordingPlayer  local files or signed cloud links   retention (pg_cron daily 03:15 UTC) → deletes expired
UI  Memories → Recordings (library + detail),                     unstarred audio, keeps all text
    Settings → Advanced (keep audio, AI cost)        work-drive → starred meeting → Google Drive (M6b)
```

Status flow of a recording: `uploading → uploaded → transcribing → transcribed → summarising → ready`
(`failed` is not used yet — failures fall back to the previous state and the sweep retries).
Chunk status: `uploaded → transcribing → done | failed` (max 3 attempts; stale after 5 min).

## 2. M3 — chunk transcription (`_shared/meetingStt.ts`)
- Gemini 2.5 Flash (fallback Flash-Lite), `thinkingBudget: 0`, JSON schema `{segments:[{start,end,speaker,text}]}`,
  times "MM:SS" relative to the chunk. Prompt: Nigerian English/Pidgin/Yoruba/Igbo/Hausa, translations in
  [brackets], numbers/naira exact, `[unclear]`, TV/radio/music → speaker **"Media"**, "JARVIS" only when the
  assistant answers. Earlier speaker labels are passed in to keep them consistent across chunks. `STT_VOCAB`
  secret (names; not set yet) is appended to the vocabulary.
- Tested locally on Law's real chunks: 5-min chunk → 84–91 lines in 13–22 s. First version labelled a sung
  nursery rhyme "JARVIS" → rule fixed ("Media").
- Times are stored per chunk; absolute time = sum of earlier chunks' `duration_s` (done in `meetingLines()`).

## 3. M4 — summary, memory, push (`_shared/meetingSummary.ts`)
- Structured output: `is_meeting, title, in_short, decisions[], action_items[{task,owner,due}], numbers[{label,value}],
  people[], follow_ups[], open_questions[], speakers[{label,name}]`. Rules: only what was said; owners/dates only
  if said; **no computed totals**; relative dates resolved but shown both ways; don't assume which speaker is Law.
- Tested: a made-up site meeting (all facts right, Mr Ade/Chika mapped to voices; first run computed a total and
  assumed Speaker 1 = Law → rules added) and Law's TV recording ("no conversation", no memory made).
- Saves one `meeting` memory (`client_id = meeting-<rec-id>`, embedded → recall/chat find it), updated in place on
  re-runs; none when `is_meeting=false`. Push: "‹title›" / "Summary ready: N action items, M decisions." with data
  `{kind: meeting, id: <rec-id>}` → tapping opens that recording.
- Law's two test recordings both reached `ready` ("no business meeting" — TV drama / kids' video). ✔

## 4. Bugs found and fixed this stretch
1. **Storage rejects an expired JWT with HTTP 400** (`"statusCode":"403"`, `"exp" claim timestamp check failed`),
   not 401 → refresh-on-401 never fired; uploads (and photo uploads) retried forever. Fix: `EchoBackend.authExpired()`.
2. **Creating a NEW Edge Function returns HTTP 500** ("Function deploy failed due to an internal error") for any
   name, with CLI 2.98.1 and 2.120.0; updating existing functions works. Workaround: Law created a placeholder
   `meeting` in the dashboard, then `supabase functions deploy meeting` works. (The Management-API create call
   was blocked by the session's permission guard.) **New background work therefore lives inside `meeting`.**
3. **Leftover chunks killed by the time limit.** `finish` transcribed leftovers 3 at a time in one background
   task; a slow Gemini fallback made batch 1 take 111 s and the ~150 s wall clock killed batch 2 → recording
   stuck at 4/6 forever. Fix: every chunk and every summary runs in **its own invocation** (self-kick with
   `x-meeting-secret`), plus the **5-minute sweep**. Verified: the sweep finished the stuck recording.
4. **Python heredoc edits mangle backslashes** (`\n` became real newlines in TS strings; `\/` lost a backslash).
   Use the Edit/Write tools for any code containing backslashes; scan for control characters afterwards.
5. `memories.client_id` has a **partial** unique index → PostgREST upsert `onConflict: client_id` fails. Look up
   then insert instead.

## 5. M5 — library + detail (phone)
- `meeting/MeetingLibrary.kt` (cloud calls + `CloudRec`, `LibraryItem` = phone ∪ cloud by id, `TLine`).
- `HomeViewModel`: `library`, `libraryInfo`, `libraryOffline`, `refreshRecordings()` (merges `meetings.saved()` with
  op `list`), `deleteRecording(id, audioOnly)`, `starRecording`, `audioKeepDays`, `aiCosts`, `meetingLib`.
- `ui/screens/RecordingsList.kt`: `LibraryStrip`, `RecordingCard` (status, gist, counts, star), `RecordingDetail`
  (Summary tab: in short, tick-able action items, number tiles, decisions, follow-ups, open questions, marked
  moments, starred/Drive line, **Ask about this meeting**; Transcript tab: search, speaker colours, tap line →
  play from there, tap name → rename speaker, marks inline, current line highlighted; header: rename, star, share
  notes as text, delete audio-only / everything; `PlayerBar`: scrubber with amber mark ticks, −15/+15, 1×/1.5×/2×).
- `RecordingPlayer` rewritten: pieces from files or signed URLs; prepare on IO; speed via PlaybackParams.
- Memories: Recordings filter shows the strip + cards (refresh every 15 s while open); meeting memories open their
  recording (by `client_id` metadata or by the title in "Meeting: ‹title› — ‹date›"); "Meeting saved" screen →
  **Open in Recordings**.
- Not built from the mockup: auto-scroll of the transcript, PDF export, per-meeting "Ask" in the Ask JARVIS screen
  (the inline Ask box covers it).

## 6. M6 — retention + phone cleanup
- `profile.audio_keep_days` (30/90/365, 0 = forever; default 90). Expiry = meeting end + days; starred = never.
  Changing the setting re-dates every ready, unstarred recording whose audio still exists.
- pg_cron `meeting-retention` (`15 3 * * *`) → op `retention` deletes storage objects, sets `audio_deleted_at`,
  `audio_bytes=0`; transcript/summary/memory stay. Verified callable (`{"deleted":0}`).
- Phone: `MeetingUploader.cleanUpLocal()` deletes the local `.m4a` files once every piece is verified in the cloud and
  the meeting is > 7 days old (meta stays; playback streams from the cloud).
- Settings → Advanced → **Keep meeting audio**.

## 7. M6b — starred → Google Drive (inert until Law's Google setup)
- Scope `drive.file` added to `GOOGLE_SCOPES` (server) and `GoogleSignInHelper.WORKSPACE_SCOPES` (app).
- `_shared/drive.ts` (`ensureFolder`, multipart `uploadFile`). Op `work-drive`: Drive › JARVIS › Recordings ›
  "‹date› ‹title›" ← `part-001.m4a…` + `notes.txt` (summary + transcript). Triggered by starring; the sweep retries
  starred meetings whose `drive_status` is '' or `not_connected`. Columns `drive_status/drive_folder_id/drive_copied_at`.
- **Untested** (no Google connection yet). The Google Cloud project must also enable the **Drive API**.

## 7b. M7 — model switchboard (`_shared/llm.ts`)
- `llmJson(job, system, user, schema, {userId, routes?})` tries `model_routes.routes` for the job in order
  (providers `anthropic` | `openrouter` | `gemini`; entries without a key are skipped; `"default"` = `CLAUDE_MODEL`),
  logs every attempt to `llm_calls` (tokens, `usd_est`, ms, ok/error). Routes are cached 60 s per instance.
- Jobs wired: `meeting_summary`, `meeting_ask`; `meeting_stt` reads its Gemini models from its route and logs usage.
  Seeded routes = Claude first, Gemini backup (STT: Flash → Flash-Lite). Prices in the route entries (`usd_in/out`
  per Mtok) or a small built-in table — **estimates**.
- OpenRouter path written (`response_format: json_schema`, `usage.include` → real cost) but **untested** (no key).
- `scripts/eval-meeting-summary.ts`: runs real transcripts through candidate models, writes
  `docs/eval/meeting-summary-<date>.md` (git-ignored, private). Needs `SUPABASE_SERVICE_ROLE_KEY` locally
  (`supabase projects api-keys --project-ref agtuimnppqbrjocuzqsk`) + provider keys.
- Settings → Advanced → **AI models & cost** (op `costs`: month total, per job calls/failed/$, model chains).
- To switch a job: `update model_routes set routes = '[{"provider":"openrouter","model":"…","usd_in":…,"usd_out":…},
  {"provider":"anthropic","model":"default"}]' where job = 'meeting_summary';` — no deploy needed (≤60 s cache).

## 8. M8 — glasses recordings: NOT built (needs the test session)
Why: the glasses' long recordings are firmware-controlled `.opus`, they clear their own storage ~30 s after a pull
(seen 10-11), and there is no known voice-start command. Plan for the session with Law: (1) record 10–30 min on the
glasses, pull with byte-length verification before anything is deleted; (2) confirm the file format/duration;
(3) feed it into the same pipeline as `source=glasses` — either remux/transcode to ~5-min AAC chunks on the phone
(MediaExtractor/MediaCodec) or teach the `chunk` op an `audio/ogg` single piece (Gemini accepts Opus; check the
150 s time limit for long files); (4) decide how a glasses recording is started/stopped.

## 9. Cloud reference (prod `agtuimnppqbrjocuzqsk`)
- Function `meeting` — **must be deployed with `--no-verify-jwt`** (also pinned in `supabase/config.toml`): user ops
  verify the JWT inside; internal ops (`work-chunk`, `work-summary`, `work-drive`, `sweep`, `retention`) check
  `x-meeting-secret` = secret `MEETING_INTERNAL_SECRET` (also in Vault as `meeting_internal_secret` for pg_cron).
  Deployed version at the end of this log: **v10**. `google-connect` v5 (drive scope).
- Migrations applied (each with `supabase db query --linked -f` + `migration repair --status applied`):
  `20261012190058_meeting_recordings`, `20261012213642_meeting_segments`, `20261014033845_meeting_sweep_cron`,
  `20261014034503_meeting_library_retention`, `20261014034515_llm_router`, `20261014040554_meeting_drive`.
- Cron jobs: `meeting-sweep` (*/5), `meeting-retention` (03:15 UTC). Extensions: pg_cron, pg_net, vault.
- Useful checks:
  `select client_id,status,error,chunks_total,(select count(*) from recording_chunks c where c.recording_id=r.id and c.status='done') done from recordings r order by started_at desc;`
  `select status,return_message,start_time from cron.job_run_details order by start_time desc limit 5;`
  `select job,model,ok,usd_est,created_at from llm_calls order by id desc limit 20;`
  Function logs: Supabase dashboard → Edge Functions → meeting → Logs.

## 10. Commits (all on master)
`d1b64cb` M3 · `0b1c365` M4 · `258acee` one-invocation-per-job + sweep · `081d51e` config verify_jwt ·
`5b1c1aa` M5–M7 cloud · `46af60d` M5–M7 phone · `461a01a` eval script · `05bf70e` M6b ·
`5487790` meeting memory → recording, Open in Recordings · + this docs commit. Pushed up to `081d51e`
(Law asked 2026-10-14); later commits are local until Law says push.

## 11. Not yet verified on the phone (Law was travelling — see TEST_PLAN_PHASE2.md)
Everything in §5–§7b was compiled, unit-tested (20 tests) and the cloud parts called where possible, but the new
phone screens have **not been seen on the device** (the Pixel was on another network, 10.125.142.141). The last APK
is built at `android/app/build/outputs/apk/prod/debug/app-prod-debug.apk` — install it first.

## 12. 2026-10-15 — first real phone test (Law, at home): 4 bugs found and fixed
App installed (192.168.1.69). TEST_PLAN step 1 (library, transcript, play-from-line, speed, search) passed.
Step 2: a real 9.6-min conversation (2+ people, informal). Pipeline speed fine (summary 77 s after Stop), but:
1. **Gemini Flash returned an EMPTY transcript for a full 5-min chunk of speech** (under heavy load; reproduced
   locally; Flash-Lite transcribed it fine). → `meetingStt.ts`: an empty answer for > ~1 min of audio is only
   believed if the next model agrees; each call capped at 60 s; 2 tries per model; overall budget for the
   ~150 s invocation. Re-run: chunk 0 went 0 → 29 lines (Flash empty → Flash-Lite).
2. **Real informal chat labelled `is_meeting=false`** ("no business agenda") → no notes, no memory, push "no
   conversation found". → summary prompt: only media/silence/noise is "not a meeting"; any real conversation gets
   notes + memory. Push says "Notes ready — tap to read the summary." when there are no actions/decisions.
3. **Saving the meeting memory failed: `memories.client_id` is a UUID column** (it was "meeting-rec-…"). Every real
   meeting would have failed at this step (the earlier test recordings were "no conversation" so never reached it).
   → `stableUuid("meeting-<rec-id>")` (SHA-256 → UUID v5 style).
4. **"Jarvis, mark that" during a meeting does nothing** — the wake word is off while recording (shared mic), so
   the summary turned it into an action item. → `withVoiceMarks()`: spoken marks are picked out of the transcript
   and saved as real marks (label = words after "mark that"; skipped within 10 s of an existing mark).
   The notification **Mark** button still works live. Also: a title the model made on an earlier run is now
   replaced on re-run (only a title Law typed is kept).
Also: transcript fragments ("my life" / "my life") from Flash-Lite are joined into turns (`mergeFragments`).
Result for the meeting: 2/2 chunks, summary + memory saved, mark at 9:20, title "Chat about AI glasses/JARVIS,
rogue AI fears, COVID's origins and Gaddafi". `meeting` deployed **v13**.

## 13. 2026-10-15 — TEST_PLAN_PHASE2 parts A–C passed (Law)
- **A** (meeting page: summary, transcript, rename speaker/title, Ask incl. "doesn't say", share, marked moment,
  memory search → recording): all working.
- **B** (airplane mode mid-recording): 6.7-min recording, 2/2 chunks done, `ready` on its own. ✅
- **C**: star → `audio_expires_at` null + `drive_status=not_connected` (correct before Google); keep-audio 90 ✓;
  delete audio-only → `audio_deleted_at` set, notes kept; delete everything → row + audio gone. ✅
- Numbers check: "The price of the product is $100" → Numbers: Product price $100 (exact, nothing invented).
- Two prompt fixes (v14): spoken "mark that" lines are app commands, never action items; no "(likely Law)" guesses.
- Cost so far: ≈ $0.16 total (6 transcriptions, 5 summaries, 1 question).
- Still untested (need Law's setup): Drive copy (Google), OpenRouter routes (key). A business-style meeting with
  owners + due dates would still be a useful accuracy check.
