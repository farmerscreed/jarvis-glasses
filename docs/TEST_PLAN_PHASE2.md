# Phase 2 (Meetings) — test plan for Law

*Written 2026-10-14 while Law was travelling. Everything below is built; none of the new screens has been tried
on the phone yet. Do the steps in order; tick each box and note anything odd (a screenshot helps). Allow about
1 hour, including one real meeting. Technical record: `docs/SESSION_LOG_2026-10-14.md`.*

## 0. Before you start (Claude does this with you)
- [ ] Phone on the same Wi-Fi as the PC → tell Claude the address from **Settings › Developer options ›
      Wireless debugging** (it changes often).
- [ ] Claude installs the latest app and starts recording the phone's logs.

## 1. The library you already have (5 min)
1. [ ] Open **Memories → Recordings**. At the top: *"Audio kept 90 days"* and the storage used.
2. [ ] Your two test recordings are listed with **"No conversation"** (they were a TV drama and a kids' video), date,
       length, and a ☆ star.
3. [ ] Tap one → it opens with **Summary** and **Transcript** tabs. Summary says it wasn't a business meeting.
4. [ ] **Transcript** tab: lines with times and speakers (TV/music shows as *Media*). Tap a line → the audio plays
       **from that line**. Try **−15 / +15** and the speed button (**1× → 1.5× → 2×**).
5. [ ] Type in **Search the transcript** → only matching lines show.

## 2. A real meeting (the main test, ~10 min + a few minutes' wait)
Two or more people, phone on the table. Talk naturally for **at least 6 minutes** and include:
a **price** (e.g. "9,500 naira a bag"), a **quantity**, a **decision** ("okay, agreed"), **who will do what by when**
("Chika will pay the deposit by Friday"), and say someone's **name** to them ("thank you, Mr Ade").
1. [ ] Start: Home → **Meeting** → Start (or say *"Jarvis, record this meeting with Mr Ade"*). Lock the phone.
2. [ ] Halfway: say *"Jarvis, mark that — price agreed"* or tap **Mark** in the notification. The notification
       shows *"Marked at …"* for 5 seconds.
3. [ ] Stop: *"Jarvis, stop recording"* or **Stop**. The "Meeting saved" screen shows → tap **Open in Recordings**.
4. [ ] The card goes **Uploading → Transcribing… → Summarising… → Ready** (the list refreshes by itself).
5. [ ] Within ~3 minutes of stopping: a notification *"‹title› — Summary ready: N action items, M decisions"*.
       Tapping it opens the meeting.
6. [ ] **Summary** is right: the price, quantity, decision, who/when are correct and **nothing is invented**.
       Note anything wrong — this is the most important check.
7. [ ] Tick an action item (circle → ✓). Leave the page and come back → still ticked.
8. [ ] **Transcript**: tap a speaker's name (e.g. *Speaker 2 ✎*) → type *Mr Ade* → every line of theirs now says Mr Ade.
9. [ ] Tap the title → rename it → it changes in the list too.
10. [ ] **Ask about this meeting**: *"When is the delivery?"* → a short answer with ▶ moments; tap one → it plays.
       Ask something that wasn't discussed → *"The meeting doesn't say."*
11. [ ] Share (↗) → WhatsApp to yourself → the notes arrive as readable text.
12. [ ] Marked moment: in Summary under *Marked moments* and as an amber tick on the scrubber; tap → plays there.
13. [ ] Memories search: *"what did Mr Ade say about the price?"* → the meeting appears; tap it → opens the recording.
       Also ask JARVIS by voice the same question.

## 3. Offline and recovery (5 min)
1. [ ] Start a recording, then turn on **airplane mode** for 6+ minutes (so a 5-minute piece finishes offline),
       turn it off, stop after ~12 minutes. The card should still end up **Ready** (pieces upload when back online).
2. [ ] While offline, open Memories → Recordings → the strip says *"Offline — showing what's on this phone"*;
       recordings on the phone still play.

## 4. Star, keep, delete (5 min)
1. [ ] ☆ a meeting → ★. Its page says *"Starred — the audio is kept forever"* (and, until Google is connected,
       *"Connect Google in Settings to also keep a copy in your Drive"*).
2. [ ] Settings → Advanced → **Keep meeting audio** → shows 90 days ✓. Pick 30 days, then back to 90.
3. [ ] Settings → Advanced → **AI models & cost** → this month's $ (cents), calls per job, and the model chain.
4. [ ] Delete → **Audio only** on a test recording → the notes and transcript stay; the player says the audio was
       deleted. Delete → **Everything** on another → it disappears from the list (and from the cloud).

## 5. Later (needs something from you first)
- [ ] **Google setup** (Internal consent screen; Gmail, Calendar, People **and Drive** APIs; Web + Android clients;
      Supabase Google provider) → connect in Settings → star a meeting → within ~5 min it appears in
      **Google Drive › JARVIS › Recordings**.
- [ ] **OpenRouter key** → Claude runs `scripts/eval-meeting-summary.ts` on 3–10 of your real meetings → you compare
      the notes side by side → the cheapest model that gets numbers/owners/dates right becomes the default.
- [ ] **`STT_VOCAB`** — names and places JARVIS should spell right (people, companies, estates, towns).
- [ ] **Glasses test session (M8)** — 30 minutes with the glasses to design glasses recordings safely.

## What "done" looks like
Phase 2 is signed off when §2 passes on **3 real meetings** (numbers/owners/dates right, summary within ~3 minutes),
§3 recovers by itself, and §4 behaves as described.
