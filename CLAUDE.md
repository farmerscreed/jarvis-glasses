# JARVIS (AI glasses companion) — read me first

**Start every session by reading `docs/SESSION_HANDOFF.md`** (the "LATEST" block at the top), then the
newest `docs/SESSION_LOG_*.md`. They hold the current state, settled decisions, verified API facts and
the build/install loop. `docs/USER_GUIDE.md` = how each feature works (mirrors the in-app Help).

Essentials, in case you read nothing else:
- **Owner:** Law (the "director"). Personal use first, product later. He wants direct, honest
  pushback, and an app a non-technical person can use (plain English, big obvious controls).
- **Public repo.** Never commit keys, `.env`, `wifi.txt`, logs, or family names. Server secrets live
  in Supabase (`jarvis-prod`, ref `agtuimnppqbrjocuzqsk`). Push only when Law asks; scan the diff first.
- **Stack:** Android Kotlin/Compose multi-module (`android/`, Hilt, OkHttp) + Supabase Edge Functions
  (Deno, `supabase/functions/`) + Claude (Anthropic API key) + Gemini (STT, fast vision, Live Translate).
- **Builds run on Law's Windows PC** at `D:\APP\jarvis` (cloud sandboxes can't reach Google Maven):
  `BUILD-JARVIS.bat` builds prodDebug and installs over wireless adb; `PHONE-LOGS.bat` pulls logcat.
  Results land in `build.log` / `phone-*.log` next to the scripts.
- **Traps:** project `Json{}` has `encodeDefaults=false` (defaulted DTO fields are dropped — use
  org.json or non-default fields); Gemini Live Translate's real setup format differs from its docs
  (see the session log); the Pixel's logcat only keeps a few minutes.
- **Keep the handoff current:** at the end of a session update `docs/SESSION_HANDOFF.md` (LATEST
  block) and add/extend a `docs/SESSION_LOG_<date>.md`, and update the in-app Help + `USER_GUIDE.md`
  when user-facing behaviour changes.
