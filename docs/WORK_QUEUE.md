# JARVIS — Work queue (read before writing any code)

*Created 2026-10-11 for the move from Claude chat/cowork sessions to **Claude Code on Law's PC**.
Order matters: **P0 must be fixed, tested on the phone with Law, and signed off before any P1+ work
starts.** Update this file as items close (move them to "Done" with the date and evidence).*

Context: `CLAUDE.md` → `docs/SESSION_HANDOFF.md` (LATEST block) → `docs/SESSION_LOG_2026-10-10.md`.

---

## 0. First 10 minutes in Claude Code (environment)

Claude Code runs **on the PC**, so it can do directly what the cloud sessions could only do by
double-clicking scripts: run Gradle, `adb install`, and **watch `adb logcat` live while Law tests**.

1. **Sync git (non-destructive).** `D:\APP\jarvis` is still checked out at the June commit `f9eeaf5`;
   this week's files were copied in by hand, so they show as modified/untracked. GitHub
   (`origin/master`, last `91bd779`+) has all of it. Do:
   ```
   git fetch origin
   git diff --stat origin/master          # expect (near) nothing but local-only files
   git reset --mixed origin/master        # moves HEAD, KEEPS the working files
   git status                             # anything still different = investigate before touching
   ```
   Never `reset --hard` / `clean` here without asking Law — `wifi.txt`, logs and `voicedbg*` are local-only.
2. **Phone link:** wireless adb. Address in `wifi.txt` (`connect=192.168.0.115:<port>`; the port
   changes whenever Law toggles Wireless debugging — ask him for the new one). `adb connect <addr>`.
3. **Build + install:** `android\gradlew.bat -p android --no-build-cache :app:assembleProdDebug` then
   `adb -s <addr> install -r android\app\build\outputs\apk\prod\debug\app-prod-debug.apk`
   (or double-click `BUILD-JARVIS.bat`). JDK 17, SDK `C:\Users\admin\AppData\Local\Android\Sdk`.
4. **Live logs during a test:** `adb -s <addr> logcat -v time -s EchoTranslate:V EchoPhrase:V EchoLatency:V AndroidRuntime:E`
   — start it BEFORE Law tests (the Pixel's buffer only holds a few minutes).
5. **Backend:** Supabase project `jarvis-prod` (`agtuimnppqbrjocuzqsk`). Translation sessions are
   saved as memories tagged `translation` — the transcript inside is the best record of what happened:
   `select created_at, text from memories where 'translation' = any(tags) order by created_at desc limit 5;`

---

## P0 — Translation repeats what Law says ("echo"). ✅ CLOSED 2026-10-11 (T1–T8 passed with Law)

> Fixed in 4 device rounds — full story in `docs/SESSION_LOG_2026-10-11.md`. Summary: own English
> detector + identity guard + echo filter on both outputs + commands regardless of tag (steps 1–4, 7);
> conversation mode is now **turn-taking** (loudspeaker speaks after you pause, both directions deaf
> while it plays); wordless/near-silent model audio is never played; AudioTrack tail flushed; safety
> net re-opens the mic after 10 s of wordless playback. Step 5 (hardware AEC) built behind pref
> `translate/aec`, OFF, not needed. Step 6 (push-to-talk) not needed. Debug log still on (slim later).

### Symptom (Law, 2026-10-10 and 2026-10-11)
In listen mode (glasses double-tap), when **Law himself speaks English**, JARVIS plays his own words
back into his ear, often several times — "repeating what I said like an echo". Earlier (10-10) in
conversation mode the two directions also translated each other in a loop.

### Evidence — saved session 2026-10-11 08:57 UTC (listen mode, memory tagged `translation, sw`)
```
[them · sw] Let me talk.  →  Let me talk.          (×3)
[them · sw] Stop. Stop.   →  Stop. Stop.            (×2)
[them · sw] Mimi sihitaji kuhusu nutrition.  →  I don't need to talk about nutrition.
[them · sw] I don't need to talk about nutrition.  →  I don't need to talk about nutrition.   (×3)
```
(The Mandarin video session at 08:56 translated correctly — the bug is specific to English speech.)

### Root causes (diagnosed, not yet fixed)
1. **Gemini mislabels Law's English** (Nigerian accent) as another language — here Swahili. Google's
   docs confirm: *"Language detection struggles with heavy accents"*, and **there is no source-
   language hint** in `translationConfig` (only `targetLanguageCode` + `echoTargetLanguage`).
   Because Gemini thinks the input is Swahili, `echoTargetLanguage:false` does NOT silence it: it
   "translates" English → English and **plays it in Law's ear**. That is the echo.
2. **Self-loop through the air:** that playback (open-ear glasses speakers, or the phone speaker when
   the glasses aren't the audio route) is picked up again by the phone mic → translated again →
   played again (the ×3 repeats). Current guards miss it:
   - `TranslationController.startMic()` mutes session A only while the **loudspeaker** plays or the
     ear audio is routed to the phone speaker — NOT while ear audio plays on Bluetooth.
   - The text echo filter (`isEcho` in `appendInput`) compares A's input only with `playedB`
     (loudspeaker output), never with `playedA` (A's own ear output).
3. **Voice commands fail** for the same reason: `handleVoiceCommand` only runs when the fragment is
   tagged English (`if (isEnglish(l) && handleVoiceCommand(text))`), so "stop" / "let me talk"
   tagged `sw` were ignored — Law could not stop it by voice.
4. `effectiveLang()` trusts Gemini's code for Latin-script text, so nothing corrects the `sw` tag.

### Fix plan (do in this order; each step is small and testable)
1. **Our own English detector.** Add `looksEnglish(text)` (share of common English function words —
   the, a, I, you, to, is, it, and, of, that, what, do, don't, me, my, let, stop, please… — over
   the fragment/line; tune on the transcripts above). In `effectiveLang`, Latin-script text that
   looks English is English whatever Gemini says. Then the existing listen-mode path shows it as an
   "ENGLISH" caption and plays nothing.
2. **Identity-translation guard.** For any line, if the output text is (nearly) the same words as
   the input text, the input was already in the target language: drop its audio (pending buffer +
   don't flush), mark the line English, don't append a duplicate. Do this check before
   `flushPending` in `onOutputText`.
3. **Self-echo filter.** Compare A's input with `playedA` too (and B's with `playedB`) in
   `appendInput`, so our own playback heard again is dropped even when it's re-labelled.
4. **Commands regardless of tag.** Run `handleVoiceCommand` on every input fragment from either
   session (the words are English even if mis-tagged), skipping text that matches recent playback.
5. **Hardware echo cancellation (experiment, measure on device).** Try
   `MediaRecorder.AudioSource.VOICE_COMMUNICATION` + `AcousticEchoCanceler.create(sessionId)` (+
   `NoiseSuppressor`) on the translator's `AudioRecord`. Do NOT call
   `AudioManager.setMode(MODE_IN_COMMUNICATION)` (it re-routes Bluetooth to SCO and breaks the
   glasses media route). Compare with/without on tests T1–T6; keep only if it helps.
6. **If conversation mode still loops:** offer push-to-talk for Law's side (hold a button / glasses
   gesture while speaking) — half-duplex is a valid fallback; discuss with Law first.
7. **Persistent debug log.** Logcat rolls over in minutes. Write one line per transcript fragment
   to `filesDir/translate-debug.log` (rolling, ~500 KB): time, session A/B, Gemini code, effective
   lang, text, gate state, echo/identity decisions. Add `run-as com.echo.companion cat
   files/translate-debug.log` to `phone-logs.ps1`. Remove or slim once P0 is closed.

Code: `android/app/src/main/kotlin/com/echo/app/translate/TranslationController.kt`
(`listener.onInputText`, `onOutputText`, `appendInput`, `isEcho`, `handleVoiceCommand`,
`effectiveLang`, `startMic`, `PcmPlayer.isPlaying`). Add unit tests for `looksEnglish`,
`effectiveLang`, `isEcho` and the identity guard using the real transcripts above (pure functions,
no device needed) — `android/app/src/test/…`.

### Acceptance tests (on the phone with Law; record results here)
| # | Setup | Do | Pass when |
|---|---|---|---|
| T1 | Listen mode, glasses connected for sound | Law speaks 5 English sentences | Nothing played in his ear; captions show them as ENGLISH once each; no repeats |
| T2 | Listen mode, glasses NOT connected (phone speaker) | Same | Same |
| T3 | Listen mode | 2-min French video | Continuous English in ear; no duplicated lines in the saved transcript |
| T4 | Listen mode | Mandarin video, then an English video | Labels switch Mandarin → English correctly; English not played back |
| T5 | Listen mode, video playing | Law: "Jarvis, stop translating" | Stops within ~2 s, "Translator off" |
| T6 | Listen → "Jarvis, let me talk" with a French speaker (or a French video + Law replying) | 3 min back-and-forth; Law speaks several sentences in a row | Each of Law's sentences spoken once in French; theirs once in English; no loop |
| T7 | Conversation | Type a reply | Translated, shown and spoken once; not re-translated |
| T8 | Listen mode | Screen off 3 min | Still translating; session saved at the end |

**Results 2026-10-11 (Law, Pixel 8 + glasses):** T1 ✅ · T2 ✅ · T3 ✅ · T4 ✅ · T5 ✅ · T7 ✅ · T8 ✅ on the
first fix build. T6 failed 3× (loudspeaker loop → mics deadlocked by wordless audio → AudioTrack tail)
and ✅ passed on the 4th build (15:38 phone time: voice "let me talk" misheard as Vietnamese still caught via the
translation, 12 turns over 3 min, French video ↔ English, no loop, "Javis, stop" ended it).

**Done = T1–T8 pass, Law confirms, the saved transcripts show no repeats, findings logged in a
session log.** Only then start P1.

---

## P1 — Verify what shipped on 2026-10-10 (quick, before new features)
- Look & research ("how much is this?") — worked once (standing fan, 10-10 13:50 UTC); try 3 objects.
- Cloud-first speech recognition — check `EchoLatency stt-source=cloud` and accuracy on Law's voice;
  Settings → Voice recognition line.
- Typing during translation; "YOU SAID" correction; screen-off service notification + Stop button.
- Law action: set Supabase secret **`STT_VOCAB`** (private words — family names etc. — for the
  transcriber). Never put those names in code (public repo).

## P2 — Next features Law asked for (his order, 2026-10-09)
1. 🟡 **Built + deployed 2026-10-11, waiting on Law's Google Cloud setup** (`SESSION_LOG_2026-10-11.md` §6). **Calendar + email drafts in the cloud** (Google OAuth, Edge Functions on the Anthropic API key —
   "Option B"). Today they need the PC Agent Bridge + Tailscale (`docs/AGENT_DELEGATION.md`). Keep the
   confirm-before-acting gates (`ASSISTANT_ROADMAP.md` v2.4). Drafts only — never send email.
2. ✅ **Built + configured 2026-10-11** (Firebase `jarvis-47d18`; session log §8 — Law to confirm the test push). **Push notifications (FCM)** for long tasks (research done, reminders). Setup steps already in
   `docs/SETUP_TUNNEL_AND_FCM.md` (needs Law's Firebase console step).
3. 🟡 **Done 2026-10-11, limited by the glasses** (they drop Wi-Fi ~30 s after a download; −2.3 s per look from removed waits; §9). **Keep the Wi-Fi Direct link warm** between photos (each look re-negotiates ~6–8 s) — makes
   look-and-research and reading signs much faster.

## P2.5 — UI redesign — ✅ BUILT + APPROVED on the phone 2026-10-12 (steps 1–6 done; step 7 — Ask/Device/Help/onboarding inner cards — still to restyle)
Build `docs/UI_REDESIGN.md` in its build order: theme + shared components → Translate → navigation/Home →
Settings → Memories → Research (+ `headline` from the research function) → Ask/Device/Help/onboarding.
Install + screenshot each step for Law. Nothing removed, only moved.

## P3 — Planned upgrades carried from earlier docs (not yet scheduled; confirm with Law)
- **Assistant roadmap** (`docs/ASSISTANT_ROADMAP.md`): v2.1 skills via tool-use in the chat path;
  **v2.2 "noticer"/heartbeat** (proactive reminders, follow-ups, daily briefing — needs FCM);
  v2.3 reach (send JARVIS to do a task, report back); v2.4 audit log of actions taken.
- **From the June handoff** (`SESSION_HANDOFF.md` §6/§9): Google One-Tap sign-in (needs OAuth client);
  on-device "Brain 0" (Gemma) spike; "which-brain" chip; cross-photo aggregate vision ("how many
  blocks today"); memory detail screens (signed-URL media); voice-path latency numbers.
- **Production roadmap** (`docs/PRODUCTION_ROADMAP.md`) for the product phase: F hardening (battery
  profiling, R8, crash telemetry), **G release engineering** (16 KB page alignment for the Vosk/
  sherpa native libs, signing, Play listing incl. foreground-service justification for the new
  `microphone` service), H ops & cost guardrails.
- Translation polish: conversation-mode speaker diarisation (who's talking), more languages in the
  quick picker, offline phrasebook.

---

## Done
- 2026-10-12 — **UI redesign** built and approved by Law ("I love the new look"). See `UI_REDESIGN.md`, session log §11–12.
- 2026-10-11 — Phase 1 built: email + calendar (awaiting Google setup), people memory ✅, find my glasses ✅, push notifications (configured), faster glasses looks (≈7.2 s).
- 2026-10-11 — **P0 translation echo/loop fixed**, T1–T8 passed on the Pixel with Law. See `SESSION_LOG_2026-10-11.md`.
- 2026-10-10 — cloud research, look & research, live translation (listen/talk/type), screen-off
  service, script-based language labels, cloud-first STT, docs + Help. See `SESSION_LOG_2026-10-10.md`.
