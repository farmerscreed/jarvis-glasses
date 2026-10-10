# Session log — 2026-10-09 → 2026-10-10: revival, research, translation, look-and-research

*The project had been idle since 2026-06-14. This session revived it and shipped cloud research,
live translation, look-and-research and better speech recognition. Everything below was built,
installed on the Pixel 8 and (where stated) tested by Law on the real glasses.*

---

## 1. Decisions Law made (don't relitigate)

| Decision | Detail |
|---|---|
| **Personal first, product later** | Build for Law's daily use now; productise later. |
| **Option B: cloud brain on the Anthropic API key** | Research (and later calendar/email) run in Supabase Edge Functions using the API key — NOT the Claude Max subscription and NOT the PC Agent Bridge. Works anywhere, no tether. Anthropic account was topped up 2026-10-09 (an empty balance caused 401s). |
| **Order of work** | Research → translation → calendar + email drafts in the cloud → push notifications. |
| **Translation languages** | Mandarin (business), French and Arabic (travel). Everything else Gemini supports works too. |
| **Translation engine** | Stay on **Google Gemini Live Translate**. Do NOT build an OpenAI comparison for now. |
| **Glasses double-click BACK** | = **Listen & translate on / off** (changed 2026-10-10 from "conversation"). "What am I looking at" stays voice-only. |
| **Listen first, converse on demand** | Default is one-way listening (TV, video, someone talking) → English in Law's ear. Two-way conversation is switched on by voice ("Jarvis, let me talk") or a button. |
| **Typing** | During translation Law can type what he wants to say; it's translated and spoken out loud. |
| **UI bar** | "Seamless and easy for any non-technical person." Big obvious buttons, plain English, no jargon. |
| **Repo stays public** | Never commit secrets or family names. Personal vocabulary for speech goes in the `STT_VOCAB` Supabase secret (not yet set). |
| **Push to GitHub** | Only when Law asks (he asked 2026-10-10). |

## 2. What was built (by area)

### 2.1 Backend — Supabase `jarvis-prod` (ref `agtuimnppqbrjocuzqsk`)
Deployed via the Supabase MCP (`deploy_edge_function`), not the CLI.

- **`research`** (new, v3) — Claude Sonnet + Anthropic server-side `web_search` tool
  (`web_search_20250305`, max 5 uses, handles `pause_turn`). Spoken-style answer (3–6 sentences, no
  markdown), prices assume Nigeria / naira unless told otherwise. Sources are built from the
  citations Claude actually used. Input `{query, history?, image?}`; output
  `{text, spoken, item, sources, searches, visual, ms}` where `text = spoken + "\nSources:\n" + urls`.
  **`image`** (base64 JPEG) = look-and-research: system prompt tells Claude the photo is what the
  wearer sees, identify the object (brand/model/labels) and research THAT; it emits an
  `ITEM: <name>` line which the function strips into `item`. Rate limit 40/hour.
- **`translate`** (new) — three ops:
  - `token` → a single-use **Gemini Live Translate** ephemeral token locked to a target language.
  - `phrase` → Claude returns `{langCode, langName, phrase, translation, romanization}` (say-it mode,
    and typed replies during translation).
  - `save` → Claude summarises a finished translation session and stores it as a memory
    (type `note`, tags `["translation", <langs>]`).
- **`vision`** (v3) — new `fast` flag → Gemini 2.5 Flash with `thinkingBudget: 0` (~1.9 s) for
  reading signs/menus; falls back to Claude Sonnet.
- **`transcribe`** (v3) — prompt now primed for a **Nigerian English accent** and the assistant's
  vocabulary (Jarvis, naira, Port Harcourt, cot, translate…), `thinkingBudget: 0`, temperature 0.
  Optional private extra words from the **`STT_VOCAB`** secret (not set yet — Law enters it in the
  dashboard; never put family names in code, the repo is public).
- **`diag`** — retired stub (returns 410). `pg_net` extension is installed (was used to test
  functions from inside Postgres because the cloud sandbox can't reach supabase.co / Google).

### 2.2 Gemini Live Translate — verified facts (the public docs were wrong)
- Model **`gemini-3.5-live-translate-preview`**.
- Token: `POST https://generativelanguage.googleapis.com/v1beta/auth_tokens` with
  `bidiGenerateContentSetup: {model, generationConfig:{responseModalities:["AUDIO"],
  translationConfig:{targetLanguageCode, echoTargetLanguage:false}}, inputAudioTranscription:{},
  outputAudioTranscription:{}}`. (`liveConnectConstraints` is rejected; transcription config must
  NOT be inside `generationConfig`.)
- WebSocket: `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained?access_token=<token>`
  (v1alpha also works). Client sends only `{setup:{model}}`.
- Audio in: 16 kHz PCM16 mono, 100 ms chunks, `realtimeInput.audio{data, mimeType:"audio/pcm;rate=16000"}`.
  Audio out: 24 kHz PCM16.
- Server messages: `serverContent.inputTranscription/outputTranscription {text, languageCode}`,
  `modelTurn.parts[].inlineData`. **No `turnComplete` is ever sent.** ~3.4 s to first audio.
  With `echoTargetLanguage:false`, input already in the target language produces no output text
  but can still stream a little audio (we drop audio that has no output text).
- One token = one session; a token occasionally 401s on first use — retry fixes it.

### 2.3 Android app
- **Research** — voice ("research …", "look up …", "find out …") and Ask JARVIS go to the cloud
  `research` function first (PC bridge only as fallback). Sources render as tappable site chips
  (`ui/components/Sources.kt`) on the Live transcript, Ask JARVIS cards and Timeline.
- **Look & research** (`HomeViewModel.lookAndResearch`, `isLookResearch`) — "how much is this",
  "where can I buy this", "what brand is this", "research this cot", "tell me more about this",
  "… in front of me". Flow: "Let me take a look" → glasses photo (`GlassesCaptureReactor.captureFresh`)
  → in parallel a fast Gemini look ("I see a black standing fan… Researching it now", ~2 s) and the
  `research` call with the image (~15–25 s) → spoken answer + sources → saved as a **photo memory**
  tagged `research`. **Verified 2026-10-10 13:50 UTC** (priced a standing fan from Jumia/Jiji).
- **Translation** (`app/.../translate/`):
  - `LiveTranslateSession` — OkHttp WebSocket client; tries each URL until `setupComplete`.
  - `TranslationController` (@Singleton) — modes **LISTEN** (session A only: any language →
    English, your ear) and **CONVERSATION** (A + session B: English → their language, phone
    loudspeaker). One phone mic feeds both. Auto-detects their language, remembers the last one
    (`prefs translate/last_lang`), pre-warms tokens, reconnects dropped sessions, collapses the
    model's occasional repetition loops, summarises + saves the session at the end.
  - **Mode switch while running**: say "Jarvis, let me talk / let's talk / reply" → conversation;
    "Jarvis, just listen" → back to listening; "stop translating" → stop. Buttons do the same.
  - **Typing**: `sayTyped()` → `translate/phrase` → shown on their panel + spoken on the loudspeaker
    (`PhraseSpeaker`). Tap "YOU SAID" to correct the last misheard sentence.
  - **Echo control** (the loop fix — see §4).
  - `PhraseSpeaker` — Android TTS → file → MediaPlayer pinned to the built-in speaker.
  - `TheirStrings` — greetings in the other person's language for their half of the screen.
- **Translate screen** (`ui/screens/TranslateScreen.kt`): Home = **Listen & translate** (primary),
  **Talk with someone** (language picker), **Read a sign or menu** (phone camera / glasses),
  **Say something in their language**. Listening = big rolling English captions + "Talk to them" +
  type box. Conversation = split screen; their half faces them (upside-down for across a table,
  one tap to flip), yours below, "Just listen" / "Stop", type box.
- **Reading signs/menus**: "translate this" / "what does this say" → glasses photo → fast vision;
  "read this with my phone" or the button → phone camera (`FileProvider`, `cache/camera`).
  Photos are shrunk (≤1600 px, EXIF-rotated) before upload: ~1.5 MB → ~300 KB.
- **Speech recognition**: when online, the cloud `transcribe` (Gemini, accent-primed) races the
  on-device Whisper and wins if it answers within 3.5 s; Whisper **base.en** (downloaded on Wi-Fi,
  ~160 MB, confirmed on the phone 2026-10-10 13:25) is the offline backup, tiny.en the last resort.
  Settings → **Voice recognition** shows which is in use. Logged as `EchoLatency stt-source=cloud|device(base.en)`.
- **Spoken cues**: "Translator on. Listening." / "Translator on, French. Go ahead and talk." /
  "Translator off." / "Saved to your memory." — the translator's mic is held silent while JARVIS
  speaks so its own voice is never translated.
- **Help** (in-app) and `docs/USER_GUIDE.md` updated for all of the above.

## 3. Build / install / debug loop (Windows PC, `D:\APP\jarvis`)
The cloud sandbox cannot reach Google Maven, so **builds run on Law's PC**. The repo copy there is
`D:\APP\jarvis` (older docs mention `C:\Users\admin\Documents\APP\jarvis` — that path is stale).

| Script (double-click) | What it does | Log |
|---|---|---|
| `BUILD-JARVIS.bat` → `build-and-install.ps1` | clean + `--no-build-cache` `assembleProdDebug`, then `adb install -r` to whatever device adb sees (works over wireless adb) | `build.log` (`BUILD OK` / `INSTALL OK`) |
| `WIFI-INSTALL.bat` → `wifi-install.ps1` | connects/pairs wireless adb from `wifi.txt` (`connect=IP:port`, optional `pair=`/`code=`), then installs | `install.log` |
| `INSTALL-JARVIS.bat` → `install-only.ps1` | USB install (Law's cable is charge-only, so this usually fails) | — |
| `PHONE-LOGS.bat` → `phone-logs.ps1` | dumps logcat tags EchoVision/Translate/Latency/Media/Phrase/P2p/Reactor/Ble/SherpaStt + lists the phone's speech-model files | `phone-HHmmss.log`, `phone-logs-run.txt` |

- SDK `C:/Users/admin/AppData/Local/Android/Sdk`, JDK Temurin 17. App id prod `com.echo.companion`.
- **Wireless debugging**: phone at `192.168.0.115`; the port changes every time Wireless debugging
  is toggled — Law reads it from the phone and it goes into `wifi.txt`. mDNS discovery is flaky; a
  direct `adb connect IP:port` works once paired.
- From a Claude session: files are synced into `D:\APP\jarvis` with the device bridge
  (`device_commit_files`; **stage under a fresh path each time** — re-committing the same staged
  path silently wrote a stale copy once), scripts are launched by double-clicking in File Explorer
  (computer-use, click-only tier). Pixel's logcat only holds a few minutes — pull logs right after a test.
- **First build after moving the repo** failed with "this and base files have different roots"
  (stale outputs from the old path) → the build script now always cleans with `--no-build-cache`.

## 4. Bugs found in Law's tests and how they were fixed

| Symptom (Law's words) | Root cause | Fix |
|---|---|---|
| "Got my first words, then stopped translating me" (10-10 morning) | Mic gate silenced BOTH directions whenever the loudspeaker played my translation | Gate per direction (A muted for the loudspeaker; B keeps hearing me) |
| "It keeps looping" (10-10 13:58 — "How are you doing?" → "Oh, you doing okay?… Who are you?…") | (a) `PcmPlayer` estimated "finished playing" from queue time, ignoring the ~1 s AudioTrack buffer → gate opened while audio was still coming out; (b) after the per-direction change, both legs of the acoustic loop could be open: loudspeaker → A → ear → B → loudspeaker…; (c) the "Translator on" cue was itself translated | (a) `PcmPlayer.isPlaying()` reads the AudioTrack playback head + 700 ms room tail; (b) A muted while the loudspeaker plays, B muted while your ear audio plays, both muted while JARVIS speaks (`holdMic`) or a typed reply plays; (c) **text echo filter**: a line whose words (≥3, ≥60 %) match something played in the last 25 s is dropped with its audio (CJK compared by character) |
| "The sound when I double-tap — is it taking a photo?" | Only a translate toggle; no spoken confirmation. If the app was fully closed the reactor fell back to Look & Ask (photo) | Spoken "Translator on…/off" cues; Help explains it |
| "Research the baby cot in front of me" → "I'm not able to see images" | Research only got the words, never a photo; "cot" heard as "coat" | Look & research (photo + web search); accent-primed cloud STT |
| "Is the new speech recogniser in use? Still many errors" | It was on-device Whisper base.en (downloaded 13:25), weak on Nigerian accent/names ("Travis") | Cloud-first STT racing Whisper; Settings line |
| Translation save summaries only had "me" lines | Same mic-gate bug | — |
| "Listening worked perfectly, then it went off when the screen went blank" (15:21 test) | Android 14+ only lets an app use the mic while visible or while it runs a **foreground service of type `microphone`**. Server logs proved the Gemini sessions stayed open (2 token calls, no reconnects) — the mic just went silent; nothing was saved | New `translate/TranslateService` (FGS type microphone + partial wake lock + low-latency Wi-Fi lock, "JARVIS is translating" notification with Stop), started/stopped with every translation. If Android refuses (double-tap with the phone locked and the app not visible) JARVIS says "Please unlock your phone, then double-tap again" |
| "It kept writing French when Mandarin was being spoken" | The header/chip showed the remembered language (French) and in listen mode never updated; Gemini's per-fragment language code is also unreliable | `effectiveLang()`: the writing system wins (Han → Mandarin, kana → Japanese, Arabic, Cyrillic…); listen mode always shows the language being spoken now; a new caption line starts when the language changes; each caption shows its language |
| "It should write English when English is spoken" | Listen mode dropped English input (only translations were shown) | English speech now appears as captions in listen mode (nothing played) |
| `"Saved to your memory"` never spoken | Checked only on the stop transition, before the summary arrived | Speak when the summary lands |

## 5. Still open / next
1. **Law to re-test** (build of 2026-10-10 ~15:40: screen-off service, language labels, English
   captions; the 15:11 build added listen-first, typing and the echo fix): double-tap listening on a French video; "Jarvis,
   let me talk"; a real two-way chat; typing a reply; look-and-research on a few objects.
2. If echo still leaks on the loudspeaker: try `VOICE_COMMUNICATION` mic source + Android
   `AcousticEchoCanceler` (needs care: communication mode can reroute audio), or push-to-talk.
3. Set the **`STT_VOCAB`** secret (family names etc.) in the Supabase dashboard.
4. **Calendar + email drafts in the cloud** (Google OAuth) — next on Law's list.
5. **Push notifications** (FCM) for long tasks.
6. Keep the Wi-Fi Direct link warm between photos (each look currently re-negotiates ~6–8 s).
7. Old open items from June (`SESSION_HANDOFF.md` §9) still stand.
