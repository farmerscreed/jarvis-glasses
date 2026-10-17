# Full test — video, site walks, glasses meetings (and a quick check of everything else)

*Written 2026-10-16 after building Phase 3 (V1–V7) and M8. Everything below is built and installed on the phone
(19:32 on 10-16) but most of it has **not been tried by a person yet**. Allow about **1½ hours**; you can split it
into the sessions marked below. Tick each box and note anything odd — a screenshot helps.*

**How we split it:** Claude watches the phone's logs and the cloud the whole time and checks the things you can't
see (Part A). You do the real-world steps (Part B) — they need hands, glasses, a site and other people.

---

## 0. Before you start (5 min — together)
- [ ] Glasses **charged above 50 %** and **off the charger**; phone charged; you're near the PC's Wi-Fi.
- [ ] Phone unlocked → tell Claude the **Wireless debugging** address (Settings › Developer options).
- [ ] Claude installs the latest app (if anything changed), starts the live logs, and says "ready".
- [ ] Keep the phone **unlocked with JARVIS open** when Claude asks — it lets Claude take screenshots to check
      the screens with you. (Claude never touches the phone when another app is open.)

---

## Part A — what Claude checks (you don't need to do anything)

**Already checked on 10-16 (before this plan):**
| ✓ | Check | Result |
|---|---|---|
| ✅ | The app builds; all automated tests pass (incl. new tests for the glasses' wrong clock) | pass |
| ✅ | Glasses → phone download connects to the **glasses**, not the printer | live: Wi-Fi up in 6 s, 3 files |
| ✅ | Video → small cloud copy → Gemini → notes → alert → key-moment photos | 2 real clips, 4 + 2 photos |
| ✅ | Half-made copies are never uploaded; unfinished videos are picked up again | live: 961 KB / 943 KB uploads |
| ✅ | Site-walk report on a sample walk: problems with times + severity, counts marked said/estimate, safety, next steps | good; push "3 problems, 3 things done" |
| ✅ | "Ask about this video" on a real video; refuses to invent ("The recording doesn't say.") | pass |
| ✅ | Videos no longer lose their searchable memory ("not a meeting" bug) | fixed; both 10-16 videos re-done |
| ✅ | Full audit 10-17: live cloud code = repo (9 stale functions redeployed), database, docs | see `SESSION_LOG_2026-10-16.md` §3 |

**During your test, for every step Claude watches and reports:**
- the glasses' replies (did they really start/stop, battery), each download (time, size, retries);
- the small copy, each upload (size check), the cloud status (Transcribing → Summarising → Ready), the alert sent;
- the cloud result itself: transcript lines, "Seen" notes, key moments, photos, the report, the memory saved, the cost;
- that **full videos stay on the phone** (only the small copy and the key-moment photos go to the cloud);
- **the PDF**: Claude copies it off the phone and reads it page by page (photos in the right places, nothing cut off);
- the things nobody has seen work yet: **does the same command stop a glasses recording**, the glasses' **audio file
  format** decoding on the Pixel, **how long** sync takes per minute of video, battery used per minute of filming.

---

**Tested by Claude on the phone, 2026-10-17 (screens driven over adb, logs + cloud checked):**
| ✓ | What | Result |
|---|---|---|
| ✅ | Record chooser, Recordings badges + Audio/Video filter, video page, key moment → video jumps there | works (label wrap fixed) |
| ✅ | Glasses video started/stopped from the app — **the same command stops it** (open question answered) | saved 0.5 s after stop |
| ✅ | Glasses download speed | 21 MB in 10 s (~2 MB/s); glasses film ~10 Mbps ≈ 75 MB/min |
| ✅ | Glasses audio started/stopped from the app — **the same command stops it** | saved instantly |
| ✅ | Glasses audio format — **was NOT readable** (bare Opus packets) → fixed (`GlassesAudio`, wrapped as Ogg) | transcribes; voice notes too |
| ✅ | Phone camera recorder (preview, timer, stop) → notes + photos | works |
| ✅ | **Site walk** (85 s of real building-site footage + spoken site-manager narration, fed in as a glasses walk) | recognised as a walk; 4/4 problems with right times + severity; counts exact; dates resolved; report page + **PDF** (2 pages, photos) |
| ✅ | **Glasses meeting** (2-min two-voice supplier meeting in the glasses' own audio format) | ≥ 2 min rule → meeting; every price, quantity, decision, owner and date correct |
| ✅ | Ask about this video ("who must fix the wiring and by when") | "Emeka must cover the exposed wires before Monday 19 Oct" |
| ✅ | Share → PDF (video notes + walk report), share sheet opens | works |
| ✅ | Delete → Everything (cloud row + copy + key-moment photos) | works; **bug fixed**: the full video stayed on the phone and would have come back |
| ✅ | Look, Research, Research what I'm looking at | work |
| ✅ | Found + fixed: app showed "Off-grid" for ~1 min after a glasses download while online | ignores blips < 5 s now |
| ⚠️ | Names/places misheard ("Rumuola" → "Romola", "Mr Ade" → "Mr Aid" from the PC voice) | needs your `STT_VOCAB` list |

## Part B — what only you can do (≈ 20 minutes — the rest above is done)
1. [ ] **A real site walk** on one of your sites (Session 4 below, steps 16–21): is the report something you'd send? What's missing?
2. [ ] **A real meeting on the glasses with a person** (Session 5, steps 23–26): did it stop when you said "stop recording"?
3. [ ] **Voice through the glasses:** "Jarvis, record a video" / "stop the video" / "start a site walk" / "stop the walk".
4. [ ] Look at the demo **site walk** and the **supplier meeting** in Recordings (made by Claude from stock footage and
       computer voices) — delete them when you've seen them (open → 🗑 → Everything).
5. [ ] Send Claude your **`STT_VOCAB`** list: site names (Rumuola …), people (Mr Ade, Emeka, Chika …), local words.

## Part B (full list, for reference)

### Session 1 — new screens with the videos you already have (10 min, sitting down)
1. [ ] **Home → Record** (the red tile, was "Meeting"). A box asks *What do you want to record?* with **Meeting
       (audio)** · **Video** · **Site walk**, and buttons **Phone / Glasses** under each.
       If the glasses aren't connected, the Glasses buttons are grey with the reason underneath. → **Cancel**.
2. [ ] **Memories → Recordings**: every card starts with **AUDIO** (blue, microphone), **VIDEO** (purple, a still from
       the video with ▶) or **SITE WALK** (green). Chips **All / Audio / Video** filter the list.
3. [ ] Tap one of the two living-room videos → one page: **video at the top** (paused), **Key moments** photos under
       it, tabs **Notes** and **Seen & said**.
   - [ ] Play the video. Tap a **key-moment photo** → the video jumps there.
   - [ ] **Seen & said**: lines with times ("Seen" = what the camera showed). Tap a line → the video jumps there.
   - [ ] **Notes** → bottom: **Ask about this video** → *"Was there a treadmill?"* → an answer with ▶ moments.
4. [ ] Also open the same video from **Memories → All** (the photo grid) → it opens the same page.
5. [ ] **Share ↗** on the video page → four choices. Try each, sending to yourself on WhatsApp:
   - [ ] **The notes (PDF with photos)** → a PDF arrives; open it — readable, photos, no cut-off text.
   - [ ] **The video** → the full video arrives.
   - [ ] **A small copy** → "Making a small copy…" then a smaller video arrives.
   - [ ] **The notes as text** → readable text.
6. [ ] Delete the leftover **"Empty recording"** (open it → 🗑 → Everything).

### Session 2 — glasses video (15 min, anywhere)
7. [ ] Say **"Jarvis, record a video"**. JARVIS says *"Recording video…"*, the glasses' **light comes on**, and a red
       bar *"Glasses filming — tap to stop"* shows in the app. Film for **1–2 minutes while talking** about what you
       see (name things, say a number, e.g. "there are four chairs here").
8. [ ] Say **"Jarvis, stop the video"** → the light goes **off**. *(Tell Claude if the light stays on — then
       double-click FRONT to stop.)*
9. [ ] Keep the glasses near the phone. Within ~5 minutes: alert **"Video notes ready"**. Tap it → the video page.
   - [ ] Notes describe what you filmed and said; the number you said is right; nothing invented.
10. [ ] **FRONT double-click** to film ~30 s, double-click again to stop → it also arrives with notes.
11. [ ] Ask JARVIS (voice or Ask): *"What did I film today?"* → it knows about these videos.

### Session 3 — phone camera (5 min)
12. [ ] **Home → Record → Video → Phone** → camera screen: big red button, flip button. Allow camera/mic if asked.
13. [ ] Tap **red** → timer runs. Flip is disabled while filming. Film ~1 minute, talking. Tap the **square** to stop
        → *"Done — back to JARVIS"*.
14. [ ] It appears in Recordings as **VIDEO · phone**; a few minutes later **"Video notes ready"**.
15. [ ] Fallback: switch the glasses **off** (or move far away), keep **JARVIS open on the phone**, and say *"Jarvis, record
        a video"* → after a few seconds JARVIS says the glasses aren't connected and **opens the phone camera**. Close it.
        (With JARVIS in the background it only says so — it never pops the camera up later.)

### Session 4 — SITE WALK (the important one, 20 min, on a real site or around the house/farm)
16. [ ] Glasses on, connected. Say **"Jarvis, start a site walk"** → *"Site walk started…"*, light on, bar
        *"Site walk — glasses filming"*.
17. [ ] Walk **3–5 minutes**, talking like you would to a site manager. Include on purpose:
        - something **done** ("the plastering here is finished"),
        - two or three **problems** ("this crack in the wall", "these wires are exposed", "no helmets"),
        - a **count** you say ("we have 40 bags of cement") and something it can count by looking,
        - a **next step with a name and a day** ("tell Emeka to fix this before Monday").
18. [ ] Say **"Jarvis, stop the walk"** → light off.
19. [ ] Within ~5–10 minutes: alert **"Site walk report ready: N problems, M things done"**. Tap it.
20. [ ] The page shows **Overview**, **Progress**, **Problems** (each with HIGH/MEDIUM/LOW, where, time and a
        **photo** — tap one → the video jumps there), **Counts** (*said / counted / estimate*), **Safety**, **Next steps**.
        - [ ] The problems match what you showed/said; nothing invented. Note anything wrong — **most important check**.
21. [ ] **Share ↗ → The report (PDF with photos)** → send to yourself on WhatsApp → open it. Would you send this to
        someone? Note what you'd change.
22. [ ] Short **site walk with the phone camera** (Record → Site walk → Phone, ~2 min) → also gets a report.

### Session 5 — meeting recorded by the glasses (15 min, with another person)
23. [ ] **Home → Record → Meeting → Glasses** (or say *"Jarvis, record this meeting on the glasses"*) → JARVIS
        says it's recording on the glasses; red bar *"Glasses recording the meeting — tap to stop"*.
24. [ ] Talk with someone for **3–5 minutes**: a price, a decision, who does what by when, a name.
25. [ ] Say **"Jarvis, stop recording"** (or tap the bar). *(Tell Claude whether the glasses actually stopped — if
        unsure, hold BACK.)*
26. [ ] Keep the glasses near the phone → within ~5–10 minutes **"Summary ready"** → Recordings shows it as **AUDIO ·
        glasses** with the usual Summary / Transcript; tap a transcript line → it plays from there.
27. [ ] **Hold BACK** on the glasses for a **short** note (< 1 minute: "remind me to call Ade") → becomes a **voice
        note** as before (not a meeting).

### Session 6 — quick check that nothing else broke (10 min)
28. [ ] Phone meeting: Record → Meeting → **Phone** → 1 minute → Stop → summary arrives.
29. [ ] **Look** tile (or FRONT single-click photo) → photo + description.
30. [ ] **Translate** → a few sentences → works.
31. [ ] **Research** → one question → answer with sources.
32. [ ] *"Jarvis, what's the time?"* / a normal chat question → answers.
33. [ ] Delete one test recording → **Audio only** keeps the notes; **Everything** removes it (for a video, also its
        key-moment photos in the cloud — Claude checks).

---

## Results (fill in, or just tell Claude)
| Session | Worked? | What was wrong / odd |
|---|---|---|
| 1 Screens | | |
| 2 Glasses video | | |
| 3 Phone camera | | |
| 4 Site walk | | |
| 5 Glasses meeting | | |
| 6 Everything else | | |

## Not in this test (still waiting on you)
Google setup (email, calendar, Drive copy), OpenRouter key (cheaper models + the model eval), `STT_VOCAB` names,
and whether to delete the Firebase admin key file in `android/app/`.
