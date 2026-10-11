# JARVIS UI redesign (agreed 2026-10-11)

*Mockups: **https://claude.ai/artifact/X1J3XY65QGdcwvntpiR5Ee** (private canvas, 9 phone screens).
Mockup sources are copied into `docs/design/redesign-2026-10/` (`.dc.html`, one per screen).
Law approved Home, Translate (4), Memories and Settings on 2026-10-11 ("I'm okay with all the other
pages") and asked for Research + Research answer, which were added the same day.*

## Why
Law: "the translation screen is too busy and not attractive… I need it to look futuristic, easy to use
and very intuitive, that anyone would love to have a go at it… but don't lose sight of the goal of
ensuring all we are trying to do is captured in the app and easy to locate." Same for Settings and the
rest of the app.

What was wrong (from screenshots of the 10-11 build):
- **Translate home** = four stacked text cards, each with a paragraph, buttons and a hint line. No
  obvious first action; the main use (listen) looked like everything else.
- **Settings** = one long column of cards with explanatory paragraphs; important things (Gmail,
  notifications) look the same as developer tools.
- **Features are hidden.** Look & Ask, research, people memory and find-my-glasses exist but are
  reachable only by voice phrases the user has to remember. Timeline and Gallery split one thing (memories)
  into two tabs.
- Status read like a debug console ("Cloud · Full", "MIC: GLASSES", raw BLE strings).

## Principles (apply to every screen)
1. **One obvious action per screen**, visually dominant (glowing cyan, large). Everything else smaller.
2. **Words only where they help.** A title + one short line; no paragraphs on main screens. Explanations
   move to Help or one tap deeper.
3. **Every feature is one tap from Home** (the "Do something" grid) — voice stays the fast path, never the
   only path.
4. **State in one word** ("Connected", "Connect", "On", "24%") on the right of a row, not sentences.
5. **Big targets**: ≥44 px everywhere, 52–56 px for primary actions, round icon buttons for secondary.
6. **Futuristic, not noisy**: near-black ground, cyan glow reserved for live/primary things, amber for
   "needs attention", soft radial glows behind heroes, thin borders, monospace micro-labels.

## Visual language (extends `ui/theme/` — keep existing tokens)
| Role | Value | Use |
|---|---|---|
| Ground | `#0A0E13` (SurfaceContainerLowest) | screen background (darker than today's `#101419`) |
| Card | `#12161B` / `#11151A`, border `#1F262B`–`#262A30` | rows, tiles, cards |
| Accent border | `#2B3A40` / `#22313A` | inputs, chips, hero cards |
| Electric cyan | `#3DDCFF` (PrimaryContainer) | primary buttons, live state, active tab |
| Pale cyan | `#BBEFFF` (Primary) | text on dark accent surfaces |
| Amber | `#FFB454` (PresenceAmber) | needs attention: "Connect", sync pending, look-and-research |
| Stop / danger | text `#FFB4AB` on `rgba(147,0,10,0.18)` | Stop buttons, Delete |
| Muted text | `#8E9CA1` / `#6F7D82` | secondary lines, older captions |
| Display font | Space Grotesk 500–700 | titles, hero labels, big captions |
| Body font | Inter 400–600 | everything readable |
| Micro-labels | JetBrains Mono 11–12 px, letter-spacing 1 px, UPPERCASE | section labels, language tags, meta |
| Radii | 28 hero cards · 20–22 cards/tiles · 16 rows · pills fully round | |
| Glow | cyan shadow 0 0 24–60 px at 20–55 % alpha | primary button, orb, live bars only |

New shared components to build in `ui/components/`: `GlowButton` (pill primary), `RoundIconButton`
(44/52/56), `FeatureTile` (icon + label, 3-column grid), `SettingsGroup` + `SettingsRow` (icon chip,
title, one-word state, chevron or switch), `MonoLabel`, `LiveBars` (animated waveform), `TurnBar`,
`FilterChips`, `SourceCard`.

## Navigation (new)
Bottom tabs: **Home · Translate · Memories · Settings** (was Live · Timeline · Gallery · Settings).
- **Translate** becomes a tab (hero feature; was a hidden icon on Live).
- **Memories** = Timeline + Gallery merged, with filters.
- **Ask JARVIS**, **Research**, **Device**, **Help** open as full screens with a back button.
- Live translation running anywhere shows a slim "Translating · tap to open" bar (replaces today's button).

## Screens

### Home (`Home.dc.html`)
JARVIS wordmark + Help. The **orb is the talk button** (tap = talk; wake word still works). Under it
"Say “Jarvis”, or tap" and ONE status line of dots: `Glasses 24%` · `Online` · `2 to sync` (amber only when
attention is needed; tapping it opens Device). **"Do something" grid** (3×2 `FeatureTile`s): Look (Look &
Ask), Translate, Ask (Ask JARVIS), Research, Remember (people memory), Find glasses. Then a "Last answer"
card. Keyboard input moves into Ask.
Backed by: `vm.talk/doTalk`, `captureAndDescribe` (Look), `translateOpen`, Ask screen, Research screen,
`rememberPerson` (needs a typed/voice entry sheet), `glassesLocator`.

### Translate — home (`Main.dc.html`)
Back · "Translate" · history (past sessions = translation memories). Language pill **"ANY LANGUAGE →
ENGLISH"** (tap = pick their language; Auto default). **Hero: 176 px glowing "Listen" button** in two thin
rings, "Hear any language in English", "or double-tap your glasses". **Three tiles**: Talk (two-way chat,
uses the pill's language), Read (signs & menus — tap asks *Phone camera* or *Glasses* in a small sheet),
Say (opens Say-it). "Last: French · 3 min — summary saved" card opens that session's memory.
Backed by: `startTranslation(LISTEN|CONVERSATION, lang)`, `readPhonePhoto`/`readWithGlasses`, phrase screen.

### Translate — listening (`Listening.dc.html`)
Top: live pill **"FRENCH → ENGLISH"** (language detected live), lag "1.2s", close. **Captions**: newest
line 32 px Space Grotesk white (live words in cyan), its original underneath in mono (`FR · …`), older
lines above fading 19→17 px and darker. `LiveBars` waveform. "In your glasses · nothing plays out loud".
**Dock**: type (opens type-to-them field), **Reply to them** (big cyan → conversation mode), Stop (red).
Warnings (glasses not connected for sound) become a slim amber strip above the dock.

### Translate — face to face (`Conversation.dc.html`)
Two rounded halves. **Theirs** on top, rotated 180° by default ("FRANÇAIS", your words translated at
30 px, their own last words small). Middle **`TurnBar`**: "Your turn — speak English" / "Their turn" /
"Speaking to them…" with live bars — shows the turn-taking built in P0; flanked by flip and transcript
round buttons. **Yours** below ("ENGLISH · YOU", their words in English 28 px cyan, original small,
"You said “…” · tap to fix"). Dock: type · Just listen · Stop.
TurnBar states map to `TranslationController`: your turn = B open & speaker idle; speaking to them =
speaker playing; their turn = A receiving.

### Translate — say it for me (`Phrase.dc.html`)
Language chips (Mandarin/French/Arabic + More), English input with a mic, **result card** (46 px
translation, romanization, plain pronunciation), **Play out loud** + **Show them** (full-screen,
flippable), Recent phrases.

### Memories (`Memories.dc.html`)
Title + count, **one search box** phrased as a question ("Who was the supplier I met last week?" —
semantic search), **filter chips** All · Photos · People · Research · Notes, day headers, **cards by kind**:
person (initials avatar, role · place, number), photo (thumbnail + caption + LOOK tag), research
(title, one-line answer, source chips). Tapping opens the existing detail screens.

### Settings (`Settings.dc.html`)
Header card: avatar initial, name, email, glasses battery. Groups (`SettingsGroup`):
- **Connections**: Glasses → Device screen; Gmail & Calendar → connect sheet (state "Connect" in amber /
  "Connected"); Notifications → (test button inside); Find my glasses → (permissions inside).
- **JARVIS**: "Jarvis" wake word (switch); Listen with app closed (switch); Personality & what it knows →
  (SOUL + facts editor).
- **Privacy & help**: Your data — export or delete →; Help, tips & voice commands →.
- Footer link: "JARVIS 0.1 · Advanced & developer tools ›" (voice recognition engine, offline floor,
  sync from glasses, developer console).

### Research — ask (`Research.dc.html`)
"What do you want to know?" in a large multi-line box with mic + send; note "SEARCHES THE WEB · CITES
SOURCES". Amber hero card **"Research what I'm looking at"** (glasses photo → look-and-research). Quick-start
chips (Price of…, Where to buy…, Compare…, Latest on…) prefill the box. **Recent research** list.
Backed by: `research(query)`, `lookAndResearch`, memories tagged `research`.

### Research — answer (`ResearchAnswer.dc.html`)
Meta line "5 SEARCHES · 18 S · SAVED", share. **What was asked** (+ the glasses photo and identified item for
look-and-research). **Answer card**: a **headline** (e.g. "₦45,000 – ₦60,000") in 34 px, the short spoken
answer, **Listen again**. **Sources** as numbered cards (title + domain, opens the page). **Follow-up** box
pinned at the bottom (keeps the research thread).
**Backend change needed:** `research` must also return `headline` — one short key answer (a price range,
a yes/no, a name). Add a `HEADLINE: …` line to the system prompt next to `ITEM:` and strip it like `item`.
Follow-ups pass the previous Q/A as `history` (already supported).

## Build order (next sessions)
1. Theme + shared components (`GlowButton`, `RoundIconButton`, `FeatureTile`, `SettingsGroup/Row`,
   `MonoLabel`, `LiveBars`, `TurnBar`, `FilterChips`, `SourceCard`); darker ground.
2. **Translate** (home, listening, face to face, say-it) — the screen Law flagged first.
3. Navigation: new tabs; Home with the feature grid; translating mini-bar.
4. Settings (groups + detail sheets).
5. Memories (merge Timeline + Gallery, filters, card kinds).
6. Research ask + answer (+ the `headline` backend change).
7. Ask JARVIS, Device, Help, onboarding restyled to match.
Each step: build, install on the Pixel, screenshot, Law reviews. Keep every existing function reachable;
nothing is removed, only moved (see the "Backed by" lines).
