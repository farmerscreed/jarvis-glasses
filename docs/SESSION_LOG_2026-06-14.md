# Session log — 2026-06-14 (Agent Delegation M0–M4 · conversation + voice-vision permanent fixes)

*A thorough, accurate record of this session. Companion to `SESSION_HANDOFF.md` (canonical handover)
and `SESSION_LOG_2026-06-13.md` (the prior session). Where this log corrects an earlier conclusion,
it says so explicitly — the goal is that nothing wrong is left on the record.*

---

## 1. Agent Delegation — the deliberate lane (M0–M4) — BUILT

JARVIS delegates heavy, multi-step, tool-using tasks to **Claude Code on the director's Max
subscription** (legit: running Claude Code the product on the sub, ≠ raw-API-via-sub). Fast voice/chat
stays on the API. Design: `docs/AGENT_DELEGATION.md`.

### M0 — local Agent Bridge ✅ built + curl-verified
- `agent-bridge/` — zero-dependency Node service wrapping `claude -p --output-format json`.
- `POST /task {prompt, cwd?, allowedTools?, disallowedTools?, appendSystemPrompt?, timeoutMs?, async?}`
  + `GET /health` + `GET /task/:id` (async). Binds **127.0.0.1 only** (phone reaches it via
  `adb reverse tcp:8765`), shared bearer token in gitignored `agent-bridge/.env`, prompt fed via
  **stdin** (no shell injection), least-privilege default tools, timeout-kills the child.
- Subscription-authed (no `ANTHROPIC_API_KEY`; the JSON `total_cost_usd` is the reported equivalent,
  not an API charge).

### M1 — research lane ✅ built; bridge-verified (voice leg not yet director-tested)
- Say **"Jarvis, research …"** → `AgentBridge.research()` sends the **RESEARCH_PRESET** as a system
  prompt (verify with WebSearch + cite; spoken style; `Sources:` line) with read-only/web tools →
  speaks the summary → saves it to the memory index as a `research`-tagged note.
- **Grounding finding (corrects an M0 misread):** the M0 claim "headless claude doesn't web-search
  (`webSearch:0`)" was a **measurement artifact** — `claude -p --output-format json` doesn't report
  client tool use; `usage.server_tool_use` counts only API server tools and stays ~0 for Claude Code's
  WebSearch even when it runs. Via `stream-json` (which emits `tool_use` blocks), the research preset
  **does** call WebSearch and returns live sources. The bridge's `toolsUsed` now reports only what json
  gives reliably (`numTurns`, `permissionDenials`).

### M2 — coding lane ✅ built; bridge-verified (temp-dir)
- "fix/write/refactor … the code/bug/function" → `AgentBridge.coding()` runs `Read,Edit,Write,Glob,
  Grep,Bash` in the repo cwd with `CODING_PRESET` (smallest diff; **never commit/push/delete**) and
  `disallowedTools` blocking `git push/commit/reset`, `rm`, `sudo`. Changes are left in the working tree
  for `git diff` review. Verified: created a file in a temp dir, spoke a summary, did not commit.
- **"commit"** → spoken **confirm** → `commitChanges()` (one local commit, no push).

### M3 — email / calendar lane ✅ built; bridge-verified
- Gmail / Google Calendar MCP confirmed reachable from headless `claude -p`.
- **Email is DRAFT-ONLY by construction** — the Gmail MCP exposes no send tool, so "never send
  autonomously" is structural, not just a prompt rule. `emailDraft()` saves a Gmail draft. Verified: a
  labelled test draft was created (director to delete).
- **Calendar read** (`calendarQuery`) is free, no confirm; **calendar add** (`calendarAdd`, create-only
  — no edit/delete) is gated by a spoken yes/no confirm (`awaitConfirmation`). Verified: listed the
  director's real calendars.

### M4 — async + task store ✅ local slice built; full Phase 2 needs director infra
- Bridge **async tickets**: `POST /task {async:true}` → `202 {status:"running"}`, work runs in the
  background, poll `GET /task/:id` → ok/error/timeout. Curl-verified (submit → running → ok).
- **`agent_tasks`** table migrated to the **local** stack (owner-only RLS) as the durable store.
- **Audit log** (`agent-bridge/audit.log`, gitignored) records every delegated task.
- **Still to do (director-gated):** app wiring (write/poll `agent_tasks`, speak-when-done), FCM push
  (Firebase not yet in the project), a hosted env with `claude login`, the prod `agent_tasks` deploy,
  Agent SDK streaming. The voice lanes are **synchronous + local** today.

### Trust / safety threaded through
Spoken confirm-before-act on commit + calendar-add; email structurally draft-only; `disallowedTools`
on the Bash lanes; audit log of every delegation.

---

## 2. Conversation ("discussion") — FIXED

The director reported discussion not working. **Two causes, neither was the agent feature itself:**
1. **Environmental (main cause):** `supabase functions serve` was not running → the chat Edge Functions
   timed out (HTTP 000) → every turn stalled ~30 s then failed; also `adb reverse tcp:54421` had
   dropped. Both restored.
2. **Stale auth token (the real blocker once functions were up):** the dev app persisted a session
   token from an **earlier local-stack instance**; local Supabase signs JWTs with rotating asymmetric
   (ES256) keys, so after the stack was recreated the token no longer verified → **every** chat & vision
   call 401'd with `JWKSNoMatchingKey` (chat went off-grid, `vision=FAILED`). Fix: cleared the stale
   session (re-login mints a valid token — proven: fresh token → `chat-stream` 200), and the app now
   **detects a dead-token 401/JWKS that survives the refresh, drops the session, and prompts re-login**
   instead of failing silently.
3. **One real bug of mine:** `isCalendarQuery` matched a bare "meeting"/"schedule"/"agenda" anywhere,
   so a normal sentence ("I have a meeting later, help me prep") was hijacked to the calendar agent.
   Tightened to require "my calendar/schedule" or an explicit scheduling question.

---

## 3. Voice-vision ("what am I looking at") — PERMANENTLY FIXED + device-verified

The headline fix of the session. The 2026-06-13 conclusion ("camera gated during a conversation, can't
fix without force-dropping A2DP") was **wrong**. The real problems were software, and are fixed:

- **Audio teardown** — `BtAudioEngine.releaseForCamera()`: end SCO → `AudioManager.MODE_NORMAL` →
  clear comm device → **~3.5 s settle** so the idle A2DP stream SUSPENDS. The camera is then reachable;
  no A2DP force-drop needed (the app can't do that anyway, and doesn't have to).
- **Deterministic capture pipeline** — `GlassesCaptureReactor.captureAndDescribe()` rewritten to OWN
  the flow under `syncMutex`: suppress the autonomous collector for its own `CaptureSaved`
  (`voiceVisionInFlight`), send the capture (retry until accepted), wait for a real new-photo event
  (`photos>0`, skipping the post-clear `photos=0` echo), pull, and describe **only the newest photo by
  filename** (the one just taken). Backlog files are drained into memories silently in the background.
- **Self-healing timeouts** — the whole ceremony is time-boxed (45 s); `doSync` is bounded and always
  tears down Wi-Fi Direct in a `finally`; each `routeNewFile` is bounded (45 s). A flaky/hung sync can
  **never** hold the mutex forever and wedge later captures (that was the "first works, rest don't" bug).

**Why it failed in earlier attempts (all now fixed):** old photo described (collector returned the
oldest backlog file first); attempts 2+ timed out (a hung Wi-Fi-Direct sync held the mutex); `vision=
FAILED` (the stale token, see §2).

**Device-verified 2026-06-14:** four consecutive "what am I looking at" turns, each captured a fresh
photo (`pulled=1 newest=<latest>.jpg`), `CaptureSaved ignored — voice-vision in flight` (no race),
`describePhoto … vision=ok`, and spoke the description of what was actually in front of the director.
No timeouts, no stuck lock.

---

## 4. Assistant memory / SOUL on dev — FIXED

The director found the SOUL "missing". Cause: the **local DB was behind the migration files** — only
3 of 6 migrations had been applied locally (the others were deployed to *prod* only), so the `profile`
table didn't exist on dev and JARVIS had no character. Applied `rate_limits`, `profile`, and
`agent_tasks` to the local stack (via `psql` + `NOTIFY pgrst,'reload schema'`) and **seeded the dev
user's `profile.soul` from `docs/assistant/SOUL.md`**. SOUL.md itself was never lost — it's in the repo
(commit `eb6efd1`); nothing from prior sessions was cleared.

**Carried gotcha:** the local DB drifts behind the migrations dir; `profile.sql`'s `create policy` is
not idempotent, so a `supabase db reset` / `migration up` would clash — re-seed the SOUL after any
reset. (Also in `SESSION_HANDOFF.md §5`.)

---

## 5. Key commits (this session, on `master`, pushed)
M0 bridge `7c8728d` · M1 research `6b0ef72` · M2+M3 coding/email/calendar `1b70aa8` · calendar-intent
fix `95e8747` · M4 local slice `51c6041` · camera teardown + stale-session recovery `f00ad57` ·
gotchas doc `6e1308f` · sync-mutex bound `31a0c00` · per-file bound `b949b2e` · local-drift doc
`c152506` · **deterministic voice-vision `068eaf9`** · (this documentation pass — see git log head).

## 6. What's verified vs. not (read before the next test session)
- **Device-verified:** discussion; **voice-vision** (repeatable); fresh dev sign-in; front-button photo;
  glasses recovered.
- **Bridge-verified by curl, voice leg NOT yet director-tested:** M1 research, M2 coding/commit,
  M3 calendar read/add, M3 email draft (via the voice loop on the glasses).
- **Not built:** full M4 (FCM/hosted/app-wiring); battery gauge; BLE re-subscribe-on-reconnect fix.

## 7. Gotchas reaffirmed this session (all in `SESSION_HANDOFF.md §5`)
- Stale session token after a local-stack recreation breaks chat+vision silently (`JWKSNoMatchingKey`).
- `supabase functions serve` must be running for chat/vision/recall; HTTP 000 = down, 401 = up.
- The local DB drifts behind the migration files (missing `profile` ⇒ SOUL "missing" on dev).
- `adb reverse` drops on any Wi-Fi toggle (and Wi-Fi-Direct sync toggles Wi-Fi) — re-assert both
  `tcp:54421` and `tcp:8765`.
