# JARVIS — how to use it

*A user-facing guide to every feature. The in-app **Help center** (the "?" top-right → Hub / Gestures /
Guides / Say) mirrors this. Last updated 2026-10-10.*

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
- **Live** — talk to JARVIS (the orb). Mic button, keyboard, ✨ (Ask JARVIS) and the **Translate**
  button. Shows status, the audio route, and the glasses battery. While translation is running, a
  "Translation live — open" button takes you back to it.
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
- **How it hears you:** online, your voice is transcribed in the cloud by a model told to expect a
  Nigerian English accent (most accurate); offline, a speech model on the phone takes over.
  **Settings → Voice recognition** shows which is in use. If a word keeps coming out wrong, type it.

## Seeing things (vision)
- Say **"What am I looking at?"** / **"Read this for me"** mid-conversation → JARVIS takes a photo and
  describes it (or reads the text) out loud.
- Or use the **glasses buttons** (below). Every capture lands in your Timeline + Gallery.

## Look & research — "how much is this?"
Look at something and ask about it — no phone, no typing:
- *"Jarvis, how much is this?"* · *"Where can I buy this?"* · *"What brand is that?"* ·
  *"Is this any good?"* · *"Research this cot."* · *"Tell me more about this."* · *"…the fan in front of me."*
- JARVIS says **"Let me take a look"**, takes a photo with the glasses, tells you within a couple of
  seconds what it sees (*"I see a black standing fan… researching it now"*), then searches the web and
  answers (~15–25 s): what it is, prices (in naira, Nigerian shops, unless you name a place), where to
  buy, a pro/con — with **sources** on screen. It's saved to your memory **with the photo** (Timeline →
  Research). If it can't read a brand, it says so and researches the closest match.

## Translate
Open **Translate** (Live screen button, or just use the glasses / your voice). Mandarin, French and
Arabic are the main ones; 70+ languages work.

**1. Listen & translate (the default) — double-click the BACK of the glasses.**
You hear **"Translator on. Listening."** From then on, whatever is said around you — a person, a video,
the TV, a meeting, in any language — comes into your ear **in English**, and the phone shows big
English captions. Nothing plays out loud. Double-click BACK again (or say *"stop translating"*) →
**"Translator off."** The session is summarised into your memory (**"Saved to your memory"**).
The double-tap does **not** take a photo. English speech is shown as captions too, and each caption
says which language it was. It **keeps working with the screen off** — a "JARVIS is translating"
notification stays up (with a Stop button). If the phone is locked when you double-tap, Android may
not allow the mic; JARVIS says "Please unlock your phone, then double-tap again".

**2. Talk back — a two-way conversation.**
While listening, say **"Jarvis, let me talk"** (or tap **Talk to them**). Hold the phone between you.
You speak English; they hear you **in their language from the phone's loudspeaker**, and their side of
the screen shows your words in their language (it starts upside-down so they can read it across a table
— tap the rotate icon to flip). You keep hearing them in English in your glasses. Say **"Jarvis, just
listen"** (or tap **Just listen**) to go back to listening only.
You can also start a conversation directly: Translate → **Start a conversation** (pick their language,
or Auto), or say *"Jarvis, translate to French."*

**3. Type instead of speaking.** During translation there's a box at the bottom: type what you want
to say in English and tap send — it's translated, shown on their side and **said out loud** in their
language. Use it when a word was misheard (tap **"YOU SAID"** to pull your last sentence into the box
and correct it) or when it's too noisy to talk.

**4. Read a sign or menu.** Say *"Jarvis, translate this"* / *"what does this say?"* while looking at it
(glasses photo), or Translate → **Phone camera** (fastest). JARVIS reads it to you in English.

**5. Say a phrase.** *"How do I say 'what is your best price' in Mandarin?"* → you hear the English, the
phone says the phrase out loud for them, and **Show them** puts it on screen in huge type.
*"Say it again"* repeats it.

**Good to know:** let them speak a sentence or two first if the language is new — JARVIS works it out
and remembers it for next time. Translation needs internet. For privacy, your glasses should be
connected for sound; if not, the app warns you and your English plays from the phone.

## Glasses buttons (decoded)
| Button | Action |
|---|---|
| **Front — single click** | Take a photo (auto-synced + captioned) |
| **Back — double-click** | **Listen & translate on/off** (any language → English in your ear). If the app is fully closed it falls back to "Look & Ask" (photo + description) |
| **Back — hold** | Start/stop a voice recording (transcribed to a note) |
| **Front — double-click** | Start/stop video |
| Right double-click / slide | Media play-pause / volume (native) |

## Ask JARVIS — the deliberate lane (get things done)
Tap **✨ on the Live screen** (or, in photo mode, from a photo) to open **Ask JARVIS** — a patient,
reviewable surface for heavier, tool-using tasks. Type or tap the **mic** to dictate. Answers come back
as cards by type:
- **Research** — *"research the best budget headphones"* → a sourced summary with tappable **source**
  chips. Runs in the cloud (works anywhere, no PC needed). Saved to your Timeline (Research filter).
  Say it by voice too: *"Jarvis, research the price of a 5 kVA inverter in Port Harcourt."*
  Ask about *"this"* (*"how much is this?"*) and it uses the glasses camera (see Look & research).
- **Calendar** — *"what's on my calendar this week?"* → an events list. *"add lunch tomorrow at noon"* →
  it **asks you to confirm**, then adds it.
- **Email** — *"draft an email to Sam about Friday"* → a Gmail **draft** (it never sends — you review +
  send). *"check my email"* → an inbox summary.
- **Coding** — *"fix the typo in the README"* → it edits the code for you to review (never commits
  unless you say *"commit"* and confirm).
> Research runs in the cloud. **Calendar, email and coding** still run on your PC (Agent Bridge); on the
> cloud build they work once a **tunnel** is set up (Tailscale), otherwise those phrases fall back to a
> normal chat answer. Moving calendar + email drafts to the cloud is next on the plan.

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
- **Double-tap the back of the glasses** to translate whatever you're hearing; **"Jarvis, let me talk"** to reply.
- Look at it and ask **"how much is this?"** — JARVIS photographs and researches it for you.
- In a noisy place, the orb shows when it's listening; pause naturally — it waits for you to finish.
- Find anything later in **Timeline** (search or the Research filter) and **Gallery**.
