# JARVIS (AI glasses companion) — read me first

> **FIRST: `docs/WORK_QUEUE.md`** (order of work; P0 translation echo closed 2026-10-11) and
> `docs/PRODUCT_PLAN.md` (Phase 1 = email + calendar in the cloud, people memory, find my glasses).
> WORK_QUEUE §0 has the Claude Code on the PC setup (wireless adb, live logcat, build/install).
> **UI work:** follow `docs/UI_REDESIGN.md` (approved redesign spec, mockups in `docs/design/redesign-2026-10/`).
> **Phase 2 (meetings):** design + milestone status `docs/PHASE2_DESIGN.md`; built 2026-10-14 (M1–M7 + M6b),
> record in `docs/SESSION_LOG_2026-10-14.md`; Law's phone test steps `docs/TEST_PLAN_PHASE2.md`.

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
- **More traps (2026-10-12/14):** creating a NEW Edge Function returns HTTP 500 on this project (updates work) —
  have Law create a placeholder in the dashboard, or add an op to an existing function; the `meeting` function must
  be deployed with `--no-verify-jwt` (pinned in `supabase/config.toml`); Storage reports an expired token as HTTP 400;
  one long job per function invocation (~150 s wall clock); Python/sed/heredoc edits mangle backslashes — use the
  Edit/Write tools for code with backslash escapes. The phone's adb address changes — ask Law.
- **Keep the handoff current:** at the end of a session update `docs/SESSION_HANDOFF.md` (LATEST
  block) and add/extend a `docs/SESSION_LOG_<date>.md`, and update the in-app Help + `USER_GUIDE.md`
  when user-facing behaviour changes.
