# JARVIS — how to use it

*A user-facing guide to every feature. The in-app **Help center** (the "?" top-right → Hub / Gestures /
Guides / Say) mirrors this. Last updated 2026-06-14.*

## The idea
Your **glasses** are the eyes, ears, and speaker; your **phone** is the brain; the **cloud** (and your
PC) do the heavy lifting. You talk, capture, and JARVIS remembers — then answers from *your own* memory,
truthfully (it says "I don't know" rather than guess). It runs offline-first and works untethered.

## Getting started
1. **Onboarding** runs on first launch (welcome → sign in → permissions → find glasses → hardware test
   → wake word). Grant all permissions (mic, Bluetooth, location, nearby devices, notifications).
2. **Sign in** with your email — you get a 6-digit code. (The dev build also has a quick test login.)
3. **Two builds:** *JARVIS* (cloud, works anywhere — untethered) and *JARVIS-dev* (local backend, for
   development). Use **JARVIS** for everyday use.

## The four tabs
- **Live** — talk to JARVIS (the orb). Mic button, keyboard, and ✨ (Ask JARVIS). Shows status, the
  audio route, and the glasses battery.
- **Timeline** — your memories as a day-by-day river. Search, tap a card to expand, and a **Research**
  filter to find delegated research.
- **Gallery** — photos captured on the glasses. Tap one for detail + **"Ask about this photo"**.
- **Settings** — account, wake word, **Glasses & device** (battery + buttons + Fix connection),
  privacy (export / delete everything), and the developer console.

## Talking to JARVIS (the fast lane)
- **Start:** tap the orb/mic, or say **"Jarvis"** (turn on hands-free). It listens, answers in your ear,
  and re-opens the mic for a natural back-and-forth until you go quiet or say "thanks / that's all".
- **Interrupt** it any time — just start talking (barge-in).
- **Remember things:** *"Remember that I parked on level 3."* → pinned instantly.
- **Ask your memory:** *"What did Sam say about the budget?"*, *"Where did I park?"*
- **Offline:** it still hears you (on-device speech) and answers from on-device memory; cloud answers
  resume when you're back online.

## Seeing things (vision)
- Say **"What am I looking at?"** / **"Read this for me"** mid-conversation → JARVIS takes a photo and
  describes it (or reads the text) out loud.
- Or use the **glasses buttons** (below). Every capture lands in your Timeline + Gallery.

## Glasses buttons (decoded)
| Button | Action |
|---|---|
| **Front — single click** | Take a photo (auto-synced + captioned) |
| **Back — double-click** | AI "Look & Ask" — describe what you see |
| **Back — hold** | Start/stop a voice recording (transcribed to a note) |
| **Front — double-click** | Start/stop video |
| Right double-click / slide | Media play-pause / volume (native) |

## Ask JARVIS — the deliberate lane (get things done)
Tap **✨ on the Live screen** (or, in photo mode, from a photo) to open **Ask JARVIS** — a patient,
reviewable surface for heavier, tool-using tasks. Type or tap the **mic** to dictate. Answers come back
as cards by type:
- **Research** — *"research the best budget headphones"* → a sourced summary with a collapsible
  **Sources** list. Saved to your Timeline (Research filter).
- **Calendar** — *"what's on my calendar this week?"* → an events list. *"add lunch tomorrow at noon"* →
  it **asks you to confirm**, then adds it.
- **Email** — *"draft an email to Sam about Friday"* → a Gmail **draft** (it never sends — you review +
  send). *"check my email"* → an inbox summary.
- **Coding** — *"fix the typo in the README"* → it edits the code for you to review (never commits
  unless you say *"commit"* and confirm).
> The agent lanes run on your PC/subscription. On the cloud build they work once a **tunnel** is set up
> (Tailscale); otherwise those phrases fall back to a normal chat answer. See `SETUP_TUNNEL_AND_FCM.md`.

## Discuss a photo (drill down)
From **Gallery → tap a photo → "Ask about this photo"**, the photo pins as context and you can
interrogate it — by text or voice:
- *"How many rods / blocks are there?"*  ·  *"Read the text you see."*  ·  *"What colour is the rocker?"*
Each question re-examines that exact photo and answers from what's visible. Tap the ✕ to go back to the
general lane.

## Your glasses' battery & connection
**Settings → Glasses & device:** battery %, Bluetooth/Wi-Fi-Direct state, the buttons map, and **"Fix
connection"** (re-establishes the link). Battery also shows on the Live screen.

## Privacy & trust
- A **red mic pill** shows whenever JARVIS is recording.
- **Outward/irreversible actions** (sending… well, email is draft-only; adding a calendar event;
  committing code) **always ask you to confirm** first.
- **Truth first:** JARVIS won't fabricate — if it doesn't know, it says so, then offers to find out.
- **Your data:** Settings → export everything (JSON) or delete everything (phone + cloud).

## Offline & untethered
- **Offline:** speech, wake word, and memory search run on-device; captures are saved and enriched when
  you reconnect.
- **Untethered:** the cloud build needs no cable — conversation, vision, and memory work over Wi-Fi/
  cellular. (Agent lanes need the tunnel.)

## Tips
- Say **"Jarvis"** (hands-free on) to start without touching anything.
- In a noisy place, the orb shows when it's listening; pause naturally — it waits for you to finish.
- Find anything later in **Timeline** (search or the Research filter) and **Gallery**.
