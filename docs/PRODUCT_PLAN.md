# JARVIS Glasses — Product Plan

*Agreed with Law (the director) on 11 Oct 2026. Source of truth for what to build next.
Live, commentable copy: https://claude.ai/code/artifact/df3280a4-afc6-40e8-a2d6-7e22af1ed7cf*

> **For Claude Code:** read this file, then `docs/SESSION_HANDOFF.md` (current state, build loop,
> gotchas) and `CLAUDE.md`. Work phase by phase, in order. Start with **Phase 1** (see "Roadmap").
> Ask Law before anything outward or irreversible. Never commit real keys — the repo is PUBLIC.

## Purpose and principles

JARVIS is a voice-first assistant for the AIMB-G2 camera glasses: the glasses are eyes, ears and a
speaker; the Pixel 8 and the cloud are the brain. The core already works. This plan decides what to
add next and what to leave out.

- **Personal first, product later.** Every feature must earn its place in Law's own week (sites, farm, travel, meetings) before it is built for others.
- **Usable by non-technical people.** One button or one sentence per feature, plain-language feedback, no settings needed to get value.
- **Build on the hardware we actually have.** No display, no live video stream, firmware-owned buttons, audio as a standard Bluetooth headset.
- **Memory is the keystone.** Every capture, conversation, translation and research result lands in one personal memory JARVIS can search later.
- **Honest and safe when it acts.** Confirm before anything outward or irreversible (sending, booking, deleting); say plainly when something failed.
- **Lowest running cost that works.** Cheapest model that does each job well (see "Cheaper models").

## Where we are today (10 Oct 2026 build)

| Capability | What it does | Status |
| --- | --- | --- |
| Voice conversation | "Jarvis" wake word or button, answers spoken in your ear, streamed | Working |
| Personal memory | Captures and conversations saved; search by meaning, works offline | Working |
| Look & Ask | "What am I looking at?" / "read this" from a glasses photo | Working |
| Look & research | "How much is this?" → photo, web research, prices and sources | Working |
| Research | "Jarvis, research…" in the cloud with sources | Working |
| Live translation | Double-tap BACK: any language into English in your ear; "let me talk" for two-way; type to them | Working — echo/loop fixed 11 Oct (T1–T8 passed); conversation takes turns |
| Sign and menu translation | Glasses or phone camera, read aloud in English | Working |
| Auto photo sync | Button press → BLE → Wi-Fi Direct pull → memory, in the background | Working |
| Voice notes | Hold BACK to record, transcribed into a note | Working, no summary |
| Video | Double-click FRONT records in firmware; app syncs + plays it | Working, no AI understanding |
| Glasses battery | Battery % on the Live screen | Working, needs a check vs real battery |
| Offline mode | Memory, recall and basic answers with no internet | Working |
| Delete everything | Wipes memory and media | Working |
| Email and calendar | Drafts (+ contacts lookup) and calendar read/add, in the cloud | Built + deployed 11 Oct; waiting on Law's Google Cloud setup |
| People memory | "Jarvis, remember this person…" → recall later; Timeline → People | Working (11 Oct) |
| Find my glasses | Location noted when the glasses disconnect; "where are my glasses?" + map | Working (11 Oct) |
| Push notifications | JARVIS can reach Law without the app open | Built + Firebase configured 11 Oct; test push to confirm |
| UI redesign | Futuristic, simpler screens; every feature one tap from Home | Built + approved on the phone 12 Oct — `docs/UI_REDESIGN.md` |

## What we will not build (for now)

| Feature | Why not |
| --- | --- |
| Live streaming / phone viewfinder | Glasses save files and hand them over Wi-Fi Direct afterwards; no live video feed |
| Equalizer / audio presets | Audio is a standard Bluetooth headset; no access to its DSP |
| Privacy-LED check | LED is firmware-controlled; app cannot read it |
| Full gesture remapping | Buttons capture in firmware; app only chooses how it reacts (already does) |
| Auto-reframe, AI highlight reels | Creator features; no value for personal use |
| Face recognition | Legal and trust risk; "people memory" by voice instead |
| Phone / WhatsApp call recording | Android blocks call audio for ordinary apps; dropped by decision 11 Oct |
| Morning briefing / noticer | On hold by decision 11 Oct |

## Features to build

### Email and calendar in the cloud (Phase 1)
Move the email-draft and calendar lanes from the PC agent bridge into Supabase Edge Functions on the
Claude API, using Google OAuth (Gmail on Law's Google Workspace). Draft-only email; calendar read/add;
always confirm before sending or adding.

### People memory (Phase 1)
"Jarvis, remember this person: Mr Ade, supplier, met at the Trans-Amadi yard." Optional photo. Later:
"who was the supplier I met last week?" Needs a person type in memory + voice tagging. Small.

### Find my glasses (Phase 1)
Phone saves its location the moment the glasses disconnect; "where are my glasses?" shows it on a
map. Small.

### Meeting capture (Phase 2) — full build design: `docs/PHASE2_DESIGN.md` (12 Oct)
"Jarvis, record this meeting" (voice-activated) → full recording → transcript → summary, decisions,
action items, numbers quoted → memory. In-person meetings only. Consent reminder before recording.

### Recordings library + smart storage (Phase 2)
Recordings need designing before meeting capture is built. Known (June recon): hold BACK records
`.opus` in the glasses; photos/video also captured in firmware; files move over Wi-Fi Direct and the
glasses delete their copy after a sync.

To design: long recordings (sync time, battery, dropped sync); library (play, transcript, summary,
search, share, delete); start/stop earcons and "am I still recording?".

Storage plan — keep the valuable part forever, the bulky part only while useful:
1. **Text forever:** transcripts, summaries, action items, photo descriptions in cloud memory.
2. **Audio compressed:** voice-quality audio (~11 MB/hour at 24 kbps), kept in the cloud 90 days (adjustable), then deleted unless starred.
3. **Photos:** full photo in the cloud, small preview on the phone.
4. **Video:** stays on the phone; only key frames + a description go to the cloud.
5. **Archive:** starred recordings/videos copy to Law's Google Drive instead of paid app storage.
6. **Safety:** glasses delete a file only after the phone confirms it has the whole file.

### Cheaper model routing (Phase 2)
All AI calls go through one switchboard (LiteLLM — Law already runs it — or OpenRouter) so models
can be swapped without an app update. Each job has an automatic backup model.

| Job | Thinking needed | Model tier |
| --- | --- | --- |
| Speech to text | Low | Gemini Flash (current) |
| Live translation | Low–medium | Gemini Live (current) |
| Meeting summaries | Medium | DeepSeek, Kimi or GLM |
| Memory tagging / distilling | Low | DeepSeek, GLM or Gemini Flash-Lite |
| Everyday chat | Medium | Cheap model first; hard questions to Claude |
| Look & Ask, look & research | High (vision) | Claude or Gemini; test Kimi/GLM vision |
| Email drafts, anything sent in Law's name | High | Claude |

China-hosted services are approved (decision 11 Oct) as long as they are reliable. Grok (xAI, US) is
also a candidate on price. Test each candidate on real tasks and check current prices before
switching.

### Site and farm walk reports (Phase 3)
"Jarvis, start a site walk" → photos and spoken notes → dated report with counts and issues ("how many
blocks went up today?", "any sick plants in house 3?"). For Vantara sites and the pepper greenhouses.
Report goes to Law only, as a **PDF or a Telegram message through the existing Prime bot** (choose
each time). Prime's VPS is off, but sending only needs Prime's bot token from an Edge Function.

### Video (Phase 3; test session early)
1. **Test session (do early):** longest video, file size, sync time, battery, heat; find the BLE command that starts video (capture it from the stock app) so "Jarvis, record a video" can work.
2. **Video into memory:** a written summary of what was seen and said (Gemini can take video directly).
3. **Walk reports by video:** record one walk, get the report.
4. **Share a clip** through Prime on Telegram.

### Private mode and export (Phase 4)
"Jarvis, go private" or a button: switches off the wake word and stops all capture and cloud calls
until "I'm back". Export everything to a file (pairs with Delete everything).

## Roadmap

| Phase | Name | Contents | Gate |
| --- | --- | --- | --- |
| 1 (now) | Finish and fix | Law tests translation fixes · email and calendar in the cloud · people memory · find my glasses | — |
| 2 | Meetings | Meeting capture · recordings library + smart storage · cheaper model routing — **built 14 Oct 2026, testing** (glasses recordings = M8, after a test session) | — |
| 3 | In the field | Site and farm walks · PDF or Telegram report · counts across photos · video into memory | Law uses JARVIS daily |
| 4 | Ready for a friend | Private mode (wake word off) · export everything · simple onboarding · privacy policy · a friend tests it | — |

## Decisions (11 Oct 2026)

| # | Question | Decision |
| --- | --- | --- |
| 1 | Build order | Finish email and calendar, then meeting capture |
| 2 | What meetings | In-person only; call recording dropped |
| 3 | Morning briefing | On hold |
| 4 | Walk reports | To Law only; PDF or Telegram (via Prime) per report |
| 5 | Starting recordings | Voice-activated; glasses recordings need a full design |
| 6 | Private mode | Also switches off the "Jarvis" wake word |
| 7 | Running cost | As low as possible; cheaper models where deep thinking isn't needed; China-hosted OK if reliable |
| 8 | First outside user | A friend tests it once the app is set |
