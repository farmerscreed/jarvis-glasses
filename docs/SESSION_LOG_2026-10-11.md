# Session log — 2026-10-11: first Claude Code session on the PC · P0 translation echo fixed

*First session run by Claude Code directly on Law's PC (`D:\APP\jarvis`): Gradle, adb install and the
phone's logs straight from the session. Work followed `docs/WORK_QUEUE.md`.*

---

## 1. Environment (WORK_QUEUE §0)
- **Git synced non-destructively:** `git fetch` → every "modified" file differed from `origin/master`
  only in line endings → `git reset --mixed origin/master` (HEAD `02cf9fe`). No file lost; `wifi.txt`
  and logs untouched. `docs/PRODUCT_PLAN.md` was the only local-only file.
- **Build/test from the session:** `JAVA_HOME=…/jdk-17.0.19.10-hotspot`, `android/gradlew.bat
  :app:testProdDebugUnitTest :app:assembleProdDebug`, then `adb -s <addr> install -r …app-prod-debug.apk`.
- **Unit tests now exist** in `:app` (JUnit 4.13.2 added to the version catalog):
  `android/app/src/test/kotlin/com/echo/app/translate/TranslateTextTest.kt` — 17 tests built from
  real transcripts.
- **Phone:** wireless adb. The mDNS name (`adb-43230DLJH001YY-…_adb-tls-connect._tcp`) sometimes stays
  attached; otherwise `adb connect 192.168.0.115:<port>` (port changes when Wireless debugging is toggled;
  it was 38791 this session). The connection dropped once mid-test — ask Law for the port.
- **Translator debug log on the phone:** `files/translate-debug.log` (≈500 KB, rolls to `.1`), one line
  per fragment/decision: session A/B, Gemini code → our language, mic gate state, PLAY / IDENTITY / ECHO /
  OWN-PLAYBACK / COMMAND / MUTED / "B speaking" / SAFETY. Pull with
  `adb shell run-as com.echo.companion cat files/translate-debug.log` (also in `PHONE-LOGS.bat`).
  This log is what cracked every round below — logcat alone would have rolled over.

## 2. Decisions Law made
| Decision | Detail |
|---|---|
| P0 first | Fix the translation echo before any Phase 1 work (as WORK_QUEUE says). |
| Same Google email | Law's Workspace email = his JARVIS sign-in email → Google sign-in links to his existing memories. |
| Email scope | Drafts only **+ contacts lookup** (so "email Mr Ade" finds the address). Inbox reading later. |
| Conversation = turn-taking | Accepted in testing: the phone speaks your translation ~1 s after you pause; nobody is heard while it speaks. |

## 3. P0 — what was wrong and what fixed it (4 device rounds)

**Round 1 (fix steps 1–4 + 7 of the queue).** New pure helpers in `translate/TranslateText.kt`:
`looksEnglish` (English function-word share; short fragments judged with their open line),
`isIdentity` (output = same words as input → input was already the target language), `isEcho` (vs.
BOTH outputs now), `allWordsPlayed` (a command made only of words we just played is ours).
`effectiveLang` makes Latin text that reads as English "en" whatever Gemini says. Identity lines are
shown as English captions and never played. Commands run on every fragment, any tag.
→ **T1–T5, T7, T8 passed. T6 (conversation) looped.**

**Round 2.** Log showed (a) session B (English → their language, loudspeaker) kept listening while the
loudspeaker played, heard its own Mandarin and re-translated it forever — once even "hearing" it as
English; (b) my identity override kept a line "English" while a Mandarin video talked non-stop, so 30 s
of Mandarin went untranslated; (c) open-ear glasses audio leaking into the phone mic was glued onto
their line. Fixes: **turn-taking** — B's audio is held until Law has paused 0.9 s (`speakWhenYouPause`,
from the mic loop), and the loudspeaker mutes BOTH directions; B output after non-English input is a
copy, dropped (`mutedOut`); identity in conversation ends the line instead of swallowing; echo swallow
ends on a different language; own-playback fragments on A are dropped.
→ echo gone, but **after Law's sentence the Mandarin video was never heard**.

**Round 3.** Log: 3+ minutes of "B speaking" every 200 ms — Gemini keeps streaming near-silent audio
after a translation; playing it kept the speaker "busy", which kept both mics muted: a deadlock. Fixes:
audio more than 4 s after the last translated words is dropped; quiet audio (peak < 400) more than
1.5 s after words is dropped; safety net — if wordless playback keeps a mic shut 10 s, playback is cut;
echo threshold 60 % → 80 % (Law's "Can you hear me now?" was dropped as an echo of "…now, did you hear?").
→ conversation worked several turns; safety net fired once.

**Round 4.** Causes found: Android's `AudioTrack` holds the last small piece of a stream until more
arrives, so `isPlaying` never saw the end → writer now pads 250 ms of silence when idle (not counted),
and a playback head that hasn't moved for 600 ms with nothing queued counts as stopped. "Jarvis, let me
talk" was transcribed as Dutch ("Laat me praten") / "Jarpis" → commands are also matched in A's English
translation (`commandOf`, line dropped, not played), and Jarvis variants (jarpis/jervis/javis/travis)
accepted. Chinese echo compared as character pairs (single shared characters dropped a real line).
→ **T6 passed**: 12 turns in 3 min, French video ↔ English, no loop, no safety cut, voice command worked
even when transcribed as Vietnamese.

**Not used:** hardware AEC (`VOICE_COMMUNICATION` + `AcousticEchoCanceler`) is built behind prefs
`translate/aec` (default off) — not needed. Push-to-talk — not needed.

## 4. Known small edges (not blocking)
- If Law's own command is mis-transcribed as another language, that language may be guessed for "their"
  side first (Vietnamese this time); it switches by itself as soon as they speak.
- While the phone speaks, nothing is heard — talking over it is lost (by design now; Help says so).
- The tail of a sentence spoken at the same moment as "stop" may start playing before it stops.
- Debug log is verbose; slim or remove later.

## 5. Next
Phase 1 per `PRODUCT_PLAN.md`, plan agreed with Law in this session: Google sign-in → "Connect Gmail &
Calendar" (server-held refresh token, `google-connect` function) → `calendar` function (read + confirmed
add) → `email` function (drafts + contacts lookup) → app wiring → people memory → find my glasses.
**Law's setup to do:** Google Cloud project (Workspace, consent screen **Internal**), enable Gmail +
Calendar + People APIs, Web + Android OAuth clients (debug SHA-1
`E0:34:A1:55:77:82:D3:A6:47:C7:0C:80:0D:B2:88:D7:E8:76:4F:02`), enable Google in Supabase Auth, put the
client secret in Supabase secrets (not in chat). Still pending: `STT_VOCAB` secret.
