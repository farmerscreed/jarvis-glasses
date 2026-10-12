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

## The four tabs (new look, October 2026)
- **Home** — the glowing **orb is the talk button** (tap it, or say "Jarvis"). Under it, one status
  line (glasses battery · online · memories waiting to sync — tap it for the glasses screen). Then
  **Do something**: **Look** (photo + "what am I looking at"), **Translate**, **Ask** (Ask JARVIS),
  **Research**, **Remember** (a person) and **Find glasses**. Your conversation or last answer shows below.
- **Translate** — one big **Listen** button, plus **Talk** (two-way), **Read** (signs & menus) and
  **Say** (a phrase for them). While a translation runs, a slim "Translating — tap to open" bar
  follows you on the other tabs.
- **Memories** — everything JARVIS has saved, in one place (it replaces Timeline + Gallery). Search
  with a question, filter **All · Photos · People · Research · Notes**, tap a photo for detail and
  **"Ask about this photo"**.
- **Settings** — you and your glasses at the top, then short lists: **Connections** (glasses,
  Gmail & Calendar, notifications, find my glasses), **JARVIS** (wake word, listen with the app closed,
  personality & memory), **Privacy & help** (export / delete, help). Advanced and developer tools are
  in the small link at the bottom.

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
  **Settings → Advanced → Voice recognition** shows which is in use. If a word keeps coming out wrong, type it.

## Seeing things (vision)
- Say **"What am I looking at?"** / **"Read this for me"** mid-conversation → JARVIS takes a photo and
  describes it (or reads the text) out loud.
- Or use the **glasses buttons** (below). Every capture lands in Memories.

## Look & research — "how much is this?"
Look at something and ask about it — no phone, no typing:
- *"Jarvis, how much is this?"* · *"Where can I buy this?"* · *"What brand is that?"* ·
  *"Is this any good?"* · *"Research this cot."* · *"Tell me more about this."* · *"…the fan in front of me."*
- JARVIS says **"Let me take a look"**, takes a photo with the glasses, tells you within a couple of
  seconds what it sees (*"I see a black standing fan… researching it now"*), then searches the web and
  answers (~15–25 s): what it is, prices (in naira, Nigerian shops, unless you name a place), where to
  buy, a pro/con — with **sources** on screen. It's saved to your memory **with the photo** (Memories →
  Research). If it can't read a brand, it says so and researches the closest match.

## People — "remember this person"
Say **"Jarvis, remember this person: Mr Ade, cement supplier from the Dangote depot, met at the
Trans-Amadi yard."** (also *"remember I met…"*, *"save a new contact…"*). JARVIS picks out the name, role,
company, where you met, and any phone or email you said, and saves it with today's date. If you say
**"this person"** and the glasses are connected, it also takes a picture so you can see who it was later
(just a picture — JARVIS doesn't recognise faces). Later, ask normally: *"who was the supplier I met last
week?"*, *"what's Mr Ade's number?"*. Memories → **People** shows everyone you've saved. Works offline
too — then it's saved exactly as you said it.

## Translate
Open the **Translate** tab (or just use the glasses / your voice). Mandarin, French and
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
While listening, say **"Jarvis, let me talk"** (or tap **Reply to them**). Hold the phone between you.
You speak English; they hear you **in their language from the phone's loudspeaker**, and their side of
the screen shows your words in their language (it starts upside-down so they can read it across a table
— tap the rotate button to flip). The glowing bar in the middle says whose turn it is ("Your turn — speak English", "Speaking to them…", "Listening to them…"). You keep hearing them in English in your glasses. Say **"Jarvis, just
listen"** (or tap **Just listen**) to go back to listening only.
**Take turns, like with an interpreter:** say your sentence, then pause — about a second later the
phone speaks it in their language. While the phone is speaking, JARVIS isn't listening (so it can never
translate its own voice in a loop), so let it finish before you or they carry on.
If it picks the wrong language at first, it switches by itself as soon as they speak.
You can also start a conversation directly: Translate → **Talk** (tap the language pill at the top first
to pick their language, or leave it on Any), or say *"Jarvis, translate to French."*

**3. Type instead of speaking.** During translation tap the **keyboard** button: type what you want
to say in English and tap send — it's translated, shown on their side and **said out loud** in their
language. Use it when a word was misheard (tap **"You said … tap to fix"** to pull your last sentence into the box
and correct it) or when it's too noisy to talk.

**4. Read a sign or menu.** Say *"Jarvis, translate this"* / *"what does this say?"* while looking at it
(glasses photo), or Translate → **Read** → **Phone camera** (fastest) or **Glasses**. JARVIS reads it to you in English.

**5. Say a phrase.** *"How do I say 'what is your best price' in Mandarin?"* → you hear the English, the
phone says the phrase out loud for them, and **Show them** puts it on screen in huge type.
*"Say it again"* repeats it. On screen: Translate → **Say** — pick the language, type the phrase, then
**Play out loud** or **Show them**.

**Good to know:** let them speak a sentence or two first if the language is new — JARVIS works it out
and remembers it for next time. Translation needs internet. For privacy, your glasses should be
connected for sound; if not, the app warns you and your English plays from the phone.

## Record a meeting
- **Start:** Home → **Meeting** → **Start recording**, or say *"Jarvis, record this meeting (with Mr Ade)"*.
  Start it while the phone is unlocked; after that you can lock the phone and put it on the table — it keeps
  recording (a "JARVIS is recording" notification with **Mark** and **Stop** stays up).
- It uses the **phone's microphone** (it hears the whole room; the glasses mic only hears you well).
- **During:** say *"mark that — price agreed"* (or tap **Mark**) to flag a moment; *"am I still recording?"*;
  **Pause/Resume** on the meeting screen. A red **"Recording · 12:34"** bar shows on every tab — tap it to open.
- **Stop:** *"stop recording"*, the notification's **Stop**, or the Stop button.
- **Find it later:** **Memories → Recordings.** Each recording shows its date, length and size. Tap it to
  play (drag the bar, **−15 s / +15 s**, or tap a **marked moment** to jump there) or **Delete** it.
- **Coming next:** the recording is sent to the cloud, written out as a transcript and summarised (decisions,
  who said what, to-dos), with a notification when it's ready. Until then recordings stay on the phone only.
- While recording, the wake word is off (the recorder uses the mic) and translation can't run at the same time.

## Find my glasses
Device screen → **Find my glasses** → **Turn on**, and allow location (then **"All the time"**, so it
works with the app closed). From then on, the moment your glasses disconnect, JARVIS notes where your
phone is. Ask **"Jarvis, where are my glasses?"** → *"They were last connected 2 hours ago, near Trans-Amadi
Road."* — and **Show on map** opens the spot in Google Maps. If they're connected right now, it says so.
It's the phone's location when the link dropped, so it's accurate to a room or a car park, not a drawer.

## Notifications
JARVIS can tell you when something it's working on is ready, even with the app closed (for example a
meeting summary). To check it works: Settings → **Notifications** → **Send a test notification** (allow
notifications when Android asks).

## Glasses buttons (decoded)
| Button | Action |
|---|---|
| **Front — single click** | Take a photo (auto-synced + captioned) |
| **Back — double-click** | **Listen & translate on/off** (any language → English in your ear). If the app is fully closed it falls back to "Look & Ask" (photo + description) |
| **Back — hold** | Start/stop a voice recording (transcribed to a note) |
| **Front — double-click** | Start/stop video |
| Right double-click / slide | Media play-pause / volume (native) |

## Ask JARVIS — the deliberate lane (get things done)
Tap **Ask** on Home (or, in photo mode, from a photo) to open **Ask JARVIS** — a patient,
reviewable surface for heavier, tool-using tasks. Type or tap the **mic** to dictate. Answers come back
as cards by type:
- **Research** — *"research the best budget headphones"* → a sourced summary with tappable **source**
  chips. Runs in the cloud (works anywhere, no PC needed). Saved to Memories (Research filter). Or tap **Research** on Home: type or speak the question, or **Research what I'm looking at** — the answer shows the key figure big, the short answer, sources, and a follow-up box.
  Say it by voice too: *"Jarvis, research the price of a 5 kVA inverter in Port Harcourt."*
  Ask about *"this"* (*"how much is this?"*) and it uses the glasses camera (see Look & research).
- **Calendar** — *"what's on my calendar this week?"* / *"am I free Thursday at 3?"* → a spoken answer +
  an events list. *"add a site meeting with Mr Ade on Tuesday at 10"* → JARVIS reads back exactly what it
  will add (*"Shall I add Site meeting with Mr Ade, Tue 14 Oct 10:00–11:00?"*) and **only a clear "yes"
  adds it**. It never changes or deletes events.
- **Email** — *"draft an email to Mr Ade about the cement delivery"* → a **draft in your Gmail** (it never
  sends — you review and send it from Gmail). JARVIS finds the address in your Google contacts; if it
  can't, the To line is left empty and it tells you.
- **Coding** — *"fix the typo in the README"* → it edits the code for you to review (never commits
  unless you say *"commit"* and confirm).
> **First, connect Google once:** Settings → **Gmail & Calendar** → **Connect Gmail & Calendar**, and
> allow access on Google's screen. Calendar and email then work anywhere — in the cloud, no PC needed.
> Disconnect any time from the same place. Research also runs in the cloud. Coding still needs the PC.

## Discuss a photo (drill down)
From **Memories → Photos → tap a photo → "Ask about this photo"**, the photo pins as context and you can
interrogate it — by text or voice:
- *"How many rods / blocks are there?"*  ·  *"Read the text you see."*  ·  *"What colour is the rocker?"*
Each question re-examines that exact photo and answers from what's visible. Tap the ✕ to go back to the
general lane.

## Your glasses' battery & connection
**Settings → Glasses & device:** battery %, Bluetooth/Wi-Fi-Direct state, the buttons map, and **"Fix
connection"** (re-establishes the link). Battery also shows on Home and at the top of Settings.

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
- Find anything later in **Memories** (search, or the Photos / People / Research filters).
