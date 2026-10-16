# Phase 3 — Video: design for Law's approval (2026-10-15)

*Law's choices (2026-10-15): all four goals — **video → memory**, **walk/site reports**, **start by voice**, **share
clips** — from **both the glasses and the phone camera**. Builds on the Phase 2 meetings pipeline
(`docs/PHASE2_DESIGN.md`) so recording, upload, retry, library, retention and cost tracking are reused, not rebuilt.*

---

## 1. What Law gets

> **On site:** "Jarvis, start a site walk." The glasses start recording (no button). Law walks the site talking
> ("block C wall still not plastered", "count the bags here"). "Jarvis, stop." A few minutes later: *"Site walk —
> report ready: 3 problems, 2 things done."* The report has a short overview, progress, problems (each with a photo
> taken from the video and the moment it was seen), rough counts, safety notes and next steps — shareable as a PDF
> on WhatsApp. Later: *"Jarvis, what did the block C wall look like last Tuesday?"* answers from the walk.
>
> **Any video** (glasses FRONT double-click, "Jarvis, record a video", or the phone camera in the app) becomes a
> memory: what was seen and said, with key moments as photos. Clips can be shared.

## 2. What we know (verified 2026-10-15) and what the test session must find out

**Known** — from the official glasses app's code (details: `docs/recon/Glasses_Controls.md`, last section):
- `02 01 02` **starts a glasses video**, `02 01 08` starts an audio recording — the same kind of Bluetooth command
  our app already uses for photos (`02 01 01`). So **"Jarvis, record a video" is feasible.**
- The official app **refuses to start video at 15 % battery or less** — why nothing happened today at 8 %.
- **Video length is a glasses setting:** 15 s · 30 s · 1 min · 3 min · 9 min · 12 min · unlimited (default 3 min).
  We can read and change it.
- Glasses videos are `.mp4` (firmware stabilised), pulled over the glasses' Wi-Fi like photos. Two June clips were
  4.6 MB each (length unknown).
- Today each synced video becomes a "photo" memory with the text "(video — file name)" and the **whole file is
  uploaded** to the `media` bucket (50 MB limit — long videos would fail).

**Test session V0 (glasses charged > 50 %, ~20 min) must measure:**
1. Read the current video-length setting; record 15 s, 1 min and 3 min clips → file size per minute, resolution.
2. Does `02 01 02` sent a second time **stop** the video (toggle)? Does FRONT double-click stop one started by command?
3. Sync time per minute of video over Wi-Fi Direct; does the glasses' storage clear itself after a pull?
4. Battery used and heat over a 3–9 min recording.

## 3. Architecture (reuses Phase 2)

```
 Glasses video (button / "Jarvis, record a video" → 02 01 02)      Phone camera (in-app recorder, CameraX)
            │ pulled over Wi-Fi Direct (existing sync)                         │
            ▼                                                                   ▼
 ORIGINAL .mp4 stays on the phone (filesDir/videos) — full quality, for playback, frames and sharing
            │ Media3 Transformer → small ANALYSIS COPY: 480p, ~1 Mbps, mono audio ≈ 8 MB/min, ≤ 5-min pieces
            ▼
 existing chunk upload (MeetingUploader → bucket `recordings`, size + sha256 check) with kind = video | walk
            ▼  cloud: `meeting` function (same jobs, sweep, retention)
 work-chunk (video): Gemini Flash reads the piece (Files API) → what was SAID (speaker lines, like meetings)
                     + what was SEEN (timestamped notes) + KEY MOMENTS (times worth a photo)
 work-summary: job video_summary (or walk_report) → notes / report → memory (type video) → push
            ▼
 phone: grabs full-quality photos from the ORIGINAL at the key moments → uploads them (op frames) → they appear
        in the notes, the report, the memory and the library card
```

- **Why an analysis copy:** a 3-minute glasses clip may be 50–100 MB; the AI doesn't need full quality, uploads on
  site data stay small, and the cloud copy costs almost nothing. Original stays on the phone.
- **Why Gemini:** it watches video with its sound in one call (≈ 300 tokens per second at normal detail ≈ $0.30
  per hour of video on Flash, less at low detail — estimate, check current pricing). Claude stays on the writing
  (summary/report) through the model switchboard, as for meetings.
- **One pipeline:** videos are rows in `recordings` (kind `video` / `walk`, source `glasses` / `phone`), so the
  library, retry/sweep, retention, star, Drive copy, Ask, delete and the cost view all work for video too.

## 4. Site/walk reports
- Start: "Jarvis, start a site walk" (glasses: sets length to unlimited for this walk, starts video; or the phone
  camera if the glasses are off), or tap **Site walk**. Stop: "Jarvis, stop the walk" / button.
- Report (structured, like meeting notes): **place & date · overview · done/progress · problems** (what, where,
  how serious, the moment + photo) **· counts** (item, approximate number, "≈" and how sure) **· safety · next
  steps · questions**. Rules as for meetings: only what was seen or said; counts are marked approximate.
- Output: report screen in the app; **PDF** (with the photos) to share on WhatsApp/email; also saved as a memory.

## 5. Phone camera
- In-app recorder (CameraX): big red button, flip camera, 720p, shows length; for when the glasses are charging or
  for a steadier shot. **Needs the screen on** (Android doesn't allow the camera with the screen off).
- Same pipeline after Stop.

## 6. Voice
- "Jarvis, record a video" / "stop the video" · "Jarvis, start a site walk" / "stop the walk" · "how long has it
  been recording?" · later questions about what was seen go through the normal memory search.
- Battery guard: below 16 % JARVIS says "The glasses' battery is too low to record video (8 %) — charge them or
  use the phone camera."

## 7. Sharing
- **Share clip** (the original from the phone; option "small version" for slow data), **share report (PDF)**,
  **share notes (text)** — through Android's share sheet (WhatsApp, Telegram, email…).
- Telegram-through-Prime is left out (Prime's server is off); the share sheet covers it.

## 8. Screens (mockups before building, as in Phase 2)
- **Home:** the **Meeting** tile becomes **Record** → *Meeting (audio) · Video · Site walk*.
- **Memories → Recordings** shows videos too (thumbnail cards with a ▶ and the length).
- **Video page** = the meeting page with a **video player on top**: *Summary* (what happened: seen + said), *Moments*
  (photos from the video — tap to jump), *Transcript* (said, with "seen" notes interleaved), Ask, share, star, delete.
- **Walk report page**: the report sections with photos; **Share PDF**.
- **Phone recorder screen** and **Settings → Glasses → Video length**.

## 9. Storage & cost
- Original video: on the phone until Law deletes it (or a later "free up space" option).
- Cloud: the small analysis copy follows the meeting-audio rule (90 days; starred forever; starred → Drive).
  Text, report, frames: forever.
- Cost: ≈ $0.30 per hour of video for the AI watching it + a few cents per summary/report. Shown in **AI models &
  cost**.

## 10. Milestones (each ends on the phone with Law)

| # | Milestone | Done when |
|---|---|---|
| **V0** | Test session (charged glasses) | §2 questions answered; decide the analysis-copy settings |
| **V1** | Glasses video control | "Jarvis, record a video"/"stop" works; battery guard; video length in Settings; long videos no longer fail upload |
| **V2** | Analysis copy + upload | 480p pieces upload through the existing pipeline (kind video), originals stay on the phone |
| **V3** | Video understanding | a 3-min clip → said + seen + moments within ~2 min; photos at the moments from the original |
| **V4** | Video notes, memory, library + video page | notification; video page with player, moments, Ask; memory answers "what did … look like" |
| **V5** | Phone camera recorder | record in the app → same notes |
| **V6** | Site walk + report + PDF | one real walk → a report Law would send to someone |
| **V7** | Sharing clips | share original / small version / PDF / text |

## 10a. Status (2026-10-16)

**2026-10-16 phone test — bugs found and fixed** (commits 98250ac, 2c928d4 + V4):
- Wi-Fi Direct fell back to *any* peer → connected to an HP printer → every glasses download timed out (also the cause of the
  10-15 "download failed"). `GlassesP2pManager` now connects only to `AIMB…` peers and re-searches until they appear.
- The uploader raced the transcoder: a half-written piece uploaded (107 KB of 943 KB) and another recording was marked finished
  with **no** pieces. `VideoIngest` now builds in a hidden `.ingest-<id>` folder and moves it into place when complete.
- The glasses' clock was ~4 months off (files named June) → recordings dated June and their local copy freed at once ("older
  than 7 days"). `VideoIngest.startedAt` trusts the file name only within 2 days before arrival.
- `catchUp()` at app start re-ingests recent glasses videos with no recording (or a broken one).
- Glasses downloads now stream to disk (a 3-min glasses video ≈ 250 MB at ~11 Mbps) with one retry.
- Glasses video length reads back 180 s (`01 02` reply works). The short clips (8–10 s) were stopped early, not a length bug.
- Wi-Fi Direct sometimes drops mid-file (seen with WhatsApp aborting at the same instant — a phone-side network event); 2 clips
  incl. Law's double-click test video were still on the glasses at the end of the session.
- Leftover: cloud row `rec-1781735045920-087385` ("Empty recording", the truncated upload) — Law to delete it in the app.

| # | Status | Notes |
|---|---|---|
| V0 | 🟡 partly | 8 % battery → no video (stock rule ≤ 15 %). At 31 %: voice start **worked** (reply `02 01 02 FF FF`, glasses saved 1 video) but it **stopped after ~11 s** (length setting not taking? — check the file). Download kept failing: the glasses' Wi-Fi took ~23 s (> our 20 s wait → now 40 s). The `0B nn` frame every 3 s is NOT a recording heartbeat. Battery frame `05 <pct> <charging>`: 3rd byte 1 = charging. |
| V1 | ✅ built, installed | voice start/stop/walk, battery guard, Video length setting, filming bar (now from the start-command reply, not the 3 s frame), 45 MB upload guard, 40 s Wi-Fi wait / 240 s sync. |
| V2 | ✅ on the phone, tested 2026-10-16 | `video/VideoIngest.kt`: Media3 Transformer → 480p ~0.7 Mbps pieces of ≤ 3 min (`NNN.mp4`) in `recordings/<id>/`; original stays (meta `original`); kind `walk` if filmed inside a walk window. Uploader/recorder handle `.mp4` pieces. |
| V3 | ✅ tested end to end 2026-10-16 (2 real glasses clips → notes + push + 4/2 key-moment photos) | `_shared/meetingVideo.ts`: Gemini Files API → said + seen ("Seen" lines) + key moments; tested on a 16 s clip (7.5–12 s). `meeting` v15: video pieces analysed, `summariseVideo` (jobs `video_summary` / `walk_report`), memory type `video`, op `frames`; phone takes photos from the original at the moments (`MeetingUploader.framesStep`). |
| V4 | 🟡 first cut on the phone (2026-10-16) | One page per video (Law: "the video and the summary on the same page"): `RecordingDetail` plays the ORIGINAL at the top (`VideoPlayer` + `VideoControl`, opens paused), "Key moments — tap to watch" strip (key-moment photos via signed URLs, or the moments before photos exist), tabs **Notes** / **Seen & said** (tap a line → video jumps there). Tapping a video in Memories opens this page directly (`MemoriesScreen` looks up the recording by `meta.original`). Recordings list: every card badged AUDIO / VIDEO / SITE WALK (videos show a still + ▶), All / Audio / Video chips (Law, 10-16). Still to do: Record tile chooser, Ask on the video page, mockup polish. |
| V5 | ✅ built 2026-10-16 (on the phone, not yet tried) | `CameraRecorderScreen` (CameraX 1.4.2, 720p→SD fallback, flip, timer, screen kept on) → `HomeViewModel.phoneVideoDone` → `VideoIngest.ingest(source "phone", kind video/walk)`; originals in `filesDir/videos`; included in `catchUp`. Home tile **Record** → `RecordChooser` (Meeting · Video · Site walk; Glasses / Phone camera, glasses disabled with the reason). Voice "record a video"/"start a site walk" opens the phone camera when the glasses aren't connected or are ≤ 15 %. |
| V6 | ✅ built 2026-10-16 | Cloud (`meeting` v17+): walk summaries carry `report` {place, overview, progress, problems[what, where, severity, t], counts[item, number, sure], safety, next_steps, questions} (`WALK_SCHEMA`, job `walk_report`, 8k tokens — routes migration `20261016160000`); push "Site walk report ready: N problems, M things done"; memory text includes the report. Phone: photos at every problem time too (`framesStep`), report section on the video page (`WalkReport`), PDF (`video/ReportPdf.kt`, A4, photos from the original). Fixed: the model set `is_meeting=false` for videos ("not a meeting") → no memory; prompt + safety net (≥ 3 real lines ⇒ true); the two 10-16 videos re-run. |
| V7 | ✅ built 2026-10-16 | Video page ↗ → PDF (report/notes with photos), full video, small 480p copy (`VideoIngest.makeSmallCopy`), notes text; FileProvider paths `glasses_media/`, `videos/`, cache `share/`. Ask box says "Ask about this video"; the cloud Ask prompt understands "Seen" lines. |

## 11. Risks
- **Time limit (~150 s per cloud job):** uploading a 40 MB piece to Gemini + waiting for it to be processed +
  analysis may not fit — split into "send to Gemini" and "analyse" jobs if needed (the sweep already retries).
- **Glasses quirks:** storage that clears itself after a pull (verify the whole file arrived first), heat and
  battery on long recordings, Wi-Fi Direct speed for big files.
- **Counts from video are approximate** — always shown with "≈" and a confidence.
- **Privacy:** video of people — same reminder as meetings ("tell people you're recording").

## 12. Decisions — Law approved all four recommendations (2026-10-15)
1. Cloud keeps the **small 480p copy for 90 days** (starred forever), like meeting audio.
2. Home: **Meeting tile → "Record"** (Meeting · Video · Site walk).
3. Glasses video length: **3 min** for normal videos, **unlimited** for site walks.
4. Walk report as a **PDF** with photos (plus the in-app report).
Not built, by decision: turning off or hiding the glasses' recording light (it tells people they're being filmed;
declined 2026-10-15 — offered to solve the underlying problem instead if Law describes it).

## 12a. The questions as asked
1. Cloud keeps the **small 480p copy for 90 days** like meeting audio (recommended), or nothing in the cloud
   after analysis (then old videos play only from the phone)?
2. **Home: Meeting tile → "Record"** chooser (Meeting · Video · Site walk) — OK?
3. Default **glasses video length**: keep 3 min for normal videos and unlimited for site walks (recommended)?
4. Walk report as **PDF** (recommended) and/or text?
