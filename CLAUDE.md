# JARVIS (AI glasses companion) — read me first

> **⚠️ FIRST: `docs/WORK_QUEUE.md`.** P0 (translation repeats/echoes Law's own speech) must be fixed,
> tested on the phone with Law and signed off **before any other work**. It also has the first-10-
> minutes setup for Claude Code on the PC (git sync, wireless adb, live logcat).

**Then read `docs/SESSION_HANDOFF.md`** (the "LATEST" block at the top), then the
newest `docs/SESSION_LOG_*.md`. They hold the current state, settled decisions, verified API facts and
the build/install loop. `docs/USER_GUIDE.md` = how each feature works (mirrors the in-app Help).

Essentials, in case you read nothing else:
- **Owner:** Law (the "director"). Personal use first, product later. He wants direct, honest
  pushback, and an app a non-technical person can use (plain English, big obvious controls).
- **Public repo.** Never commit keys, `.env`, `wifi.txt`, logs, or family names. Server secrets live
  in Supabase (`jarvis-prod`, ref `agtuimnppqbrjocuzqsk`). Push only when Law asks; scan the diff first.
- **Stack:** Android Kotlin/Compose multi-module (`android/`, Hilt, OkHttp) + Supabase Edge Functions
  (Deno, `supabase/functions/`) + Claude (Anthropic API key) + Gemini (STT, fast vision, Live Translate).
- **Builds run on Law's Windows PC** at `D:\APP\jarvis` (cloud sandboxes can't reach Google Maven).
  In Claude Code on the PC run Gradle/adb directly (see WORK_QUEUE §0); the double-click scripts
  (`BUILD-JARVIS.bat`, `PHONE-LOGS.bat`) remain for when Law runs things himself.
- **Traps:** project `Json{}` has `encodeDefaults=false` (defaulted DTO fields are dropped — use
  org.json or non-default fields); Gemini Live Translate's real setup format differs from its docs
  (see the session log); the Pixel's logcat only keeps a few minutes.
- **Keep the handoff current:** at the end of a session update `docs/SESSION_HANDOFF.md` (LATEST
  block) and add/extend a `docs/SESSION_LOG_<date>.md`, and update the in-app Help + `USER_GUIDE.md`
  when user-facing behaviour changes.
