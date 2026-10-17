# Session log — 2026-10-15 → 10-17: Phase 3 (video, site walks, sharing) + M8 (glasses meetings) + full audit

*Technical record so a new session can continue without re-explaining. Status tables live in
`docs/PHASE3_VIDEO_DESIGN.md` §10a (video) and `docs/PHASE2_DESIGN.md` (M8); Law's test steps in
`docs/TEST_PLAN_PHASE3.md`; user-facing behaviour in `docs/USER_GUIDE.md` + the in-app Help.*

---

## 1. What was built (commits `d4130ad` … `94d7f71`, + the 10-17 audit commit)

| Area | Where | Notes |
|---|---|---|
| **V1** glasses video by voice | `GlassesBleManager.toggleVideo/setVideoLength/readVideoLength`, `video/VideoCommands.kt`, `HomeViewModel.startGlassesVideo/stopGlassesVideo` | `02 01 02` start (reply `BC 41 … 02 01 02 err work`, ok = 0/255), `02 02 00 lo hi` length (read back via `01 02`: 180 s confirmed 10-16). Battery guard ≤ 15 %. "Recording" only after the glasses confirm. Red bar. |
| **V2** analysis copy | `video/VideoIngest.kt` | Media3 Transformer 1.6.1 → 480p ~0.7 Mbps `NNN.mp4` ≤ 3 min; built in hidden `recordings/.ingest-<id>/`, moved into place when complete; original stays (meta `original`). |
| **V3** video understanding | `_shared/meetingVideo.ts`, `meeting/index.ts` | Gemini Files API → said + "Seen" lines + key moments (`recording_chunks.moments`); job `video_stt` (route Flash → Flash-Lite). Phone grabs full-quality photos at the moments (`MeetingUploader.framesStep`) → media bucket → op `frames`. |
| **V4** video page | `ui/screens/RecordingsList.kt` (`RecordingDetail`, `KeyMoments`, `VideoPlayer`+`VideoControl`) | One page: original video on top (paused), key-moment strip (tap → seek), tabs **Notes / Seen & said** (tap a line → seek), Ask about this video. Video memories and the Photos grid open it. Recordings cards badged AUDIO / VIDEO / SITE WALK + All/Audio/Video chips. |
| **V5** phone camera | `ui/screens/CameraRecorderScreen.kt`, `RecordChooser.kt` | CameraX 1.4.2 (HD → SD fallback), flip, timer, screen kept on, wake word paused while open. → `HomeViewModel.phoneVideoDone` → `VideoIngest.ingest(source "phone", kind video/walk)`; originals `filesDir/videos`. Home tile **Record** → Meeting (phone/glasses mic) · Video · Site walk (glasses/phone camera). |
| **V6** site walk report | `_shared/meetingSummary.ts` (`WALK_SCHEMA`, `report`), `WalkReport` composable, `video/ReportPdf.kt` | report {place, overview, progress, problems[what, where, severity, t s], counts[item, number, sure], safety, next_steps, questions}; job `walk_report` (8k tokens). Photos at every problem time too. PDF A4 with photos from the original. Push "Site walk report ready: N problems, M things done". |
| **V7** sharing | video page ↗ | PDF (report/notes), full video, small 480p copy (`VideoIngest.makeSmallCopy`), notes text; FileProvider `glasses_media/`, `videos/`, cache `share/`. |
| **M8** glasses meetings | `GlassesBleManager.toggleAudioRecording/setAudioLength`, `VideoIngest.ingestAudio`, `GlassesCaptureReactor` | `02 01 08` start/stop, `02 06 00 lo hi` length (stock 1800/3600/7200; we set 7200). Synced `.opus` → AAC `NNN.m4a` ≤ 5 min → normal meeting pipeline (kind meeting, source glasses). Rule: the newest glasses audio after an app-started glasses meeting, or any glasses audio ≥ 2 min = meeting; shorter = voice note. |

## 2. Bugs found on the phone 10-16 and fixed
1. **Wi-Fi Direct connected to an HP printer** (fallback "any peer") → every download timed out with 0 files (also the 10-15
   "download failed"). `GlassesP2pManager` now only connects to `AIMB…` peers and re-searches until they appear. Live after
   the fix: glasses Wi-Fi up in ~6 s.
2. **Uploader raced the transcoder:** one piece uploaded half-written (107 KB of 943 KB); another recording marked finished with
   no pieces. Fixed by building off to the side (`.ingest-<id>`). `catchUp()` (app start) re-ingests recent videos with no
   recording or a broken one.
3. **Glasses clock ~4 months off** (files named June in October) → recordings dated June, local copy freed at once.
   `VideoIngest.startedAt` trusts the name only within 2 days before arrival; else arrival − length.
4. **Downloads held the whole file in memory** (a 3-min glasses video ≈ 250 MB at ~11 Mbps) → now streamed to disk, one retry.
5. **"Not a meeting" for videos:** Claude set `is_meeting=false` for videos → no memory, push "nothing to note". Prompt clarified
   + safety net (≥ 3 real lines ⇒ true); the two 10-16 videos were re-run.
6. Wi-Fi Direct can still drop mid-file (WhatsApp aborted at the same instant — a phone-side network event); the remaining two
   clips (incl. Law's double-click video) came across on the next sync and were processed fully.

## 3. Audit 2026-10-17 (Law: "check everything is properly done, documented and pushed")
- Git clean and pushed; secrets/logs ignored (`.env`, `google-services.json`, Firebase admin key, logs). 28 app unit tests × 4
  variants pass; all 17 Edge Functions type-check. (`lint` across modules fails on the old local `sherpa-onnx` AAR in
  `:device:audio` — pre-existing, unrelated.)
- **Deployed code vs repo:** 10 functions downloaded and diffed = identical except `remember` (June copy of `_shared/profile.ts`).
  8 older functions can't be downloaded (old bundle format). Redeployed from the repo: remember, distill, profile, calendar,
  email, ingest, recall, chat, chat-stream (verify_jwt unchanged = true; boot-checked: each answers 400/401 from its own code).
  `meeting` redeployed (v18+) with the fixes below. `diag` = retired stub (410), documented 10-10.
- DB: 20 migrations local = remote; pg_cron `meeting-sweep` (5 min) + `meeting-retention` (03:15) active; Vault secret present;
  no stuck recordings. Added model route `video_stt` (Flash → Flash-Lite).
- **Fixed in the audit:**
  - Voice "record a video" with the glasses briefly disconnected now **reconnects first**, and only opens the phone camera if
    JARVIS is on screen (before: the camera would pop up later when the app was opened). Same reconnect for glasses meetings.
  - "Walk/meeting recorded on the glasses" goes to the **newest** synced file of that type, not the oldest of a backlog.
  - **Full video originals were being uploaded** to the media bucket (via the old PHOTO memory, if ≤ 45 MB) — against the
    approved design; stopped (`MemoryStore.syncOne` skips `.mp4`). 6 originals (78 MB) already in the bucket — Law to decide.
  - Delete → **Everything** now also removes a video's key-moment photos.
  - App understands memory type `video` (opens the video page; label Video / Site walk) — before it showed as a Note.
  - Wake word / conversation / translation let go of the mic while the phone camera records; camera provider loaded off the
    main thread.
  - Cost screen names the video jobs; old "Home → Meeting" instructions → "Home → Record".

## 3b. Testing 2026-10-17 (Claude on the phone; Law asked "can't you perform most of them yourself?")
Method: screens driven over adb (only while JARVIS had focus), live logcat, cloud DB checks. Simulations: a **site walk** =
4 free Mixkit construction clips (85 s) + Windows-voice narration (ffmpeg, installed with `scoop install ffmpeg`), pushed into
`glasses_media/` as the newest file with `pending_walk` set; a **glasses meeting** = 2-min two-voice supplier dialogue encoded
with libopus 16 kbps CBR 20 ms 16 kHz mono → exactly the glasses' 40-byte packets, pushed as the newest `.opus`.
(Git Bash rewrites `/data/...` paths for adb → use `MSYS_NO_PATHCONV=1`.)

**Answered:** `02 01 02` and `02 01 08` sent again STOP the recording (reply work=2 / work=8, file saved at once). Glasses video
≈ 10 Mbps (21 MB / 16 s); Wi-Fi Direct download ≈ 2 MB/s; a 16 s clip synced in 17.5 s.

**Bugs found and fixed:**
1. **Glasses audio wasn't readable** — `.opus` files are bare 40-byte Opus packets (Jieli `OpusManager`, `OpusOption{1 ch,
   16 kHz, packetSize 40}`), not Ogg. `video/GlassesAudio.kt` wraps them as Ogg Opus (RFC 7845, own CRC) → Media3 → AAC
   32 kbps (2 h ≈ 30 MB). Also fixes glasses **voice notes** (were sent to the cloud unreadable). Format check compares the
   TOC's top 6 bits — real files mix 0x4B and 0x48 (first version missed a file starting with 0x48). Tests: `GlassesAudioTest`.
2. A failed glasses meeting was lost (neither meeting nor voice note) → falls back to a voice note now.
3. Orphan import turned a glasses meeting's audio into a duplicate voice note → recordings' originals are skipped.
4. Cloud stored every recording's source as "phone" → the phone sends `source`, `meeting` stores it; 6 rows corrected.
5. "Off-grid" shown ~1 min after a glasses download while online → `ConnectivityGovernor` ignores blips < 5 s and re-checks
   every 30 s.
6. Delete → Everything left the full video on the phone (and catch-up would have re-made it) → original + its photo-grid
   memory deleted too; dialog says "Cloud copy only" for videos.
7. Layout: video page Notes tab scrolls the video + key moments away (report was a quarter of the screen with large text);
   Seen & said keeps it pinned. Chooser buttons "Phone / Glasses" (the "Glasses mic" label wrapped). Walk cards say
   "N problems · M done / under way". PDF: "15 s" not "1 min", photos 640 px (was 960; 3.6 MB for 4 photos).

**Quality:** walk report 4/4 problems at the right times (0:17/0:39/0:57) with sensible severity, counts exact and marked
"said", "before Monday" → Mon 19 Oct, roof slab → Thu 22 Oct; it also described the footage (bricks, crane). Meeting summary:
every number/decision/owner right; "next week Tuesday" (said on a Saturday) → 27 Oct is debatable; names/places misheard
(needs `STT_VOCAB`).

**Left on the phone/cloud for Law to look at:** the demo site walk (`rec-1792258015187-f33c2a`), the demo supplier meeting
(`rec-1792258546864-6e490c`), Claude's 16 s glasses test video + 14 s glasses audio test (+ its voice note). The phone-camera
test clip was deleted (that's how bug 6 was found).

## 4. Verified vs not yet verified
**Verified:** voice video start (reply FF FF); video length read-back 180 s; glasses → phone download (fixed); 480p ingest
(10 s/14 MB → ~960 KB); upload size + sha check; Gemini said/seen/moments; video notes + push + key-moment photos (4 videos);
walk report on a sample transcript; Ask about this video on a real video (refuses to invent).
**Not yet verified (TEST_PLAN_PHASE3 covers them):** `02 01 02` / `02 01 08` sent again **stops** (toggle?); `02 01 08` reply
bytes; glasses `.opus` decoding by Media3 on the Pixel; walk/meeting "next file" matching; CameraX recording on the Pixel; the
PDF layout on paper; small-copy speed for long videos; sync time + battery per minute of video.

## 5. Cloud reference (additions)
- `recordings.kind` video | walk | meeting, `source` glasses | phone; `recordings.frames` [{t, path, why}];
  `recording_chunks.moments` [{t, why}] (chunk-relative; op `get` returns them on the whole timeline).
- Bucket `recordings` allows `video/mp4` up to 100 MB; memories type `video` (tags video | walk).
- Model routes: `video_stt`, `video_summary`, `walk_report` (migration `20261016160000_video_routes.sql`).
- Ops: `frames`; `ask` works for video ("Seen" lines); `delete` also removes frames.
