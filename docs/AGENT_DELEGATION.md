# JARVIS Agent Delegation — heavy "do things" via Claude Code (design doc)

*The design for JARVIS's **deliberate lane**: delegating heavy, multi-step, tool-using tasks
(research, coding, email/calendar) to **Claude Code** running on the director's **Max subscription**.
JARVIS stays the fast voice + glasses front-end; Claude Code is the agent backend that actually does
the work. Author this fully before building (director's standing process). Companion to
`docs/ASSISTANT_ROADMAP.md` (v2.1 skills) and `docs/ASSISTANT_MEMORY.md`.*

## 1. Why this, and the subscription model (important)
JARVIS today answers from memory + a single Claude API call (the **fast reflexive lane**). It can't
*do* multi-step work — browse, edit files, run code, send email. That's the **deliberate lane**, and
the cleanest engine for it is **Claude Code**, which already has the tools (web, files, shell, code,
and MCP servers: Gmail/Calendar/Drive/Notion/…).

**Subscription clarification (refines the prior "subscription can't back the app" rule):**
- ❌ Still off-limits: using Pro/Max **credentials to make raw Anthropic API calls** for the app's
  chat/voice loop (ToS/account risk). The fast lane stays on the **API**.
- ✅ Legitimate and intended: running **Claude Code itself** (the product), logged in with the
  director's **Max subscription** (`claude login`), as a delegated agent. Claude Code Max is *designed*
  for this agentic use. So **the heavy lane runs on the $100/mo subscription** — no extra per-task API
  cost. (Caveat: the Max subscription has usage limits; very heavy agent use can throttle.)

**The split:** fast voice/chat = API · heavy "do things" = Claude Code on the Max subscription. This
is exactly the "Two-Speed Brain" already in the roadmap.

## 2. Architecture — dual approach (director's plan)

### Phase 1 — local, synchronous (set up + prove the loop on this machine)
A small **JARVIS Agent Bridge** on the director's PC (where Claude Code already lives, v2.1.175):
- An HTTP service (Node or Python) that receives a task and runs Claude Code **headless**:
  `claude -p "<task>" --output-format json --permission-mode <…>` (or the **Agent SDK** for finer
  control / streaming), in a chosen **working directory**, authed via the Max subscription.
- Returns the result (final text + structured metadata: files touched, tools used, status).
- JARVIS (Android app) calls the bridge over the local network — same `adb reverse` bridge as the dev
  Supabase backend (`adb reverse tcp:<port> tcp:<port>`), or the LAN IP.
- Synchronous first (JARVIS waits, shows progress) to prove the end-to-end loop simply.

```
glasses/voice → JARVIS app → (HTTP) → Agent Bridge (PC) → claude -p (Max sub, tools) → result → spoken summary
```

### Phase 2 — hosted + async (production)
- Move the bridge to a **hosted environment** (a VM/container with Claude Code installed + `claude
  login`'d to the subscription, or an API key there). The director sets Claude Code up per environment.
- **Async**: JARVIS submits a task → gets a **ticket id** → the agent works in the background → JARVIS
  is **notified (FCM push)** + speaks a summary when done. Long tasks (minutes) never block the voice
  loop. (This is where the planned heartbeat/noticer + push layer plugs in.)
- A **task store** (Supabase table: `agent_tasks` — id, prompt, status, result, env, created/updated)
  so tickets survive, are reviewable in the app, and drive the notification.

## 3. The Agent Bridge (Phase 1 spec)
- `POST /task { prompt, cwd?, env?, allowedTools?, timeoutMs? }` → runs `claude -p` (or SDK) →
  `{ id, status, result, summary, toolsUsed, durationMs }`.
- `GET /task/:id` (for async/streaming later) → progress + result.
- Auth between app↔bridge: a shared token (bridge is on the LAN/tunnel, not public).
- Runs Claude Code with a **scoped working directory** + an **allowed-tools** policy per task type
  (research = web+read; coding = a repo dir + edit/run; email = the relevant MCP only).
- Captures Claude Code's JSON output (`--output-format json` gives the final result + usage); the
  Agent SDK (`@anthropic-ai/claude-agent-sdk`, `claude-code-sdk` py) gives streaming + tool events for
  the richer UX in Phase 2.

## 4. Task dispatch (voice → deliberate lane)
- **Explicit triggers** in the conversation loop: *"Jarvis, research …"*, *"… look into …"*,
  *"… write/fix the code …"*, *"… draft/send an email …"*, *"… add to my calendar …"*. Detected like
  the existing `isRememberCommand`/`isVisionCommand` intents, routed to the bridge instead of the
  fast chat.
- JARVIS confirms scope briefly ("On it — researching X; I'll tell you when it's done"), submits the
  task, and (Phase 2) returns to the conversation while it runs.
- Working context is resolved from the request + memory (which repo, which accounts).

## 5. Capability waves (build order — director: research → coding → email/calendar)
1. **Research / web** *(first).* "Research X and summarize." Lowest risk (read-only, no side effects),
   highest immediate value, easy to verify. Claude Code with web/search tools → a sourced summary,
   spoken + saved (and distilled into memory). Proves the whole loop safely.
2. **Coding** *(second).* "Fix this bug / add this feature in <repo>." Runs in a repo working dir with
   edit/run tools. Side effects are local + git-reviewable (diffs, branches) — confirm before commit/
   push. Naturally powerful on the director's own machine (Phase 1).
3. **Email / calendar actions** *(third).* "Draft/send an email", "add to my calendar." Outward-facing
   and irreversible → strongest trust gates (always confirm before send). Uses Claude Code's MCP
   servers (Gmail/Calendar) authed as the director.

## 6. Trust & safety (a chief of staff that *acts*)
- **Confirmation gates** for anything outward-facing or hard to undo (send email, push code, delete,
  spend) — spoken confirm-before-act. Read-only research needs none.
- **Audit log**: every delegated task + what it did (tools, files, messages) recorded and reviewable.
- **Truthful reporting** (the SOUL charter extended to actions): report what was done, and failures
  honestly ("I tried X, it failed because Y") — never silent, never faked success.
- **Scoped permissions** per task type (Claude Code `--allowedTools` / permission modes), least
  privilege; the working dir is bounded.

## 7. Memory & identity loop
- Before acting, the agent is given the director's **profile (SOUL + facts)** + relevant memories as
  context, so it acts in character and with knowledge of the director.
- Durable results **distill back** into the memory profile (so delegated work makes JARVIS know the
  director better).
- The agent acts **as the director** (their accounts via MCP) — auth delegation designed per env.

## 8. Multi-environment
Claude Code set up + logged in (subscription) in each environment the director uses (home PC, a hosted
VM, etc.). A task names (or JARVIS infers) which environment runs it — e.g. coding on the machine with
the repo, research anywhere. The task store tracks `env`.

## 9. Open decisions (to settle before/while building)
- **Headless `claude -p` vs the Agent SDK** for the bridge (SDK gives streaming + tool events → better
  async UX; `-p` is simplest to start). *Lean: start with `-p --output-format json`, move to the SDK
  for Phase 2 streaming.*
- **Bridge language**: Node (`@anthropic-ai/claude-agent-sdk`) vs Python (`claude-code-sdk`).
- **App↔bridge transport** in Phase 2: tunnel (Tailscale/Cloudflare) vs the hosted service being
  directly reachable.
- **Subscription throttling** strategy when limits hit (queue/retry, or fall back to API for that task).
- **Per-task-type allowed-tools / permission** presets.

## 10. Build plan
- **M0 (Phase 1 bridge):** ✅ **DONE + verified (2026-06-13).** Zero-dep Node bridge at `agent-bridge/`
  (`server.js`) wrapping `claude -p --output-format json`; `POST /task {prompt, cwd?, allowedTools?,
  timeoutMs?}` + `GET /health`. Binds **127.0.0.1 only**, shared bearer token (gitignored
  `agent-bridge/.env`), prompt via **stdin** (no shell/injection), least-privilege default tools
  (research preset: `WebSearch,WebFetch,Read,Glob,Grep`), timeout-kill. Returns `{id,status,result,
  summary,toolsUsed,durationMs,cost,sessionId,raw}`. **Verified by curl:** `/health` ok, POST without
  token → 401, a **research** prompt returned a sourced summary in ~28 s. Subscription-authed (no
  `ANTHROPIC_API_KEY`; `total_cost_usd` is the reported equivalent, not an API charge). Run: `node
  agent-bridge\server.js`. *(no app changes yet.)*
- **M1 (voice → research):** app dispatches "Jarvis, research…" to the bridge (dev flavor, `adb
  reverse`), speaks the summary, distills it into memory. End-to-end on this machine.
- **M2 (coding):** add a repo working-dir + edit/run tools + git-diff review + confirm-before-commit.
- **M3 (email/calendar):** MCP (Gmail/Calendar) + confirm-before-send gates.
- **M4 (Phase 2 async + hosted):** `agent_tasks` store, ticketed async, FCM push + spoken "done",
  bridge moved to a hosted env. Agent SDK streaming.
- Trust/audit + memory-distill threaded through every milestone.

**Status:** M0 + M1 (app side) built; M1 bridge-side grounding verified (2026-06-14); on-device voice
leg needs a live test. Claude Code verified present (v2.1.175, headless).

### M0/M1 finding — research grounding + a measurement gotcha (RESOLVED)
First read (M0) was that headless `claude -p` "doesn't web-search" because the JSON showed `webSearch:0`.
**That was a measurement artifact, corrected at M1 (2026-06-14):** `claude -p --output-format json` does
**not** report which *client* tools (WebSearch/WebFetch/Read/…) ran. `usage.server_tool_use.web_search_
requests` counts only **API server-side** tools and stays ~0 in Claude Code even when WebSearch actually
ran. Verified via `--output-format stream-json --verbose` (which DOES emit `tool_use` blocks): with the
**M1 research preset** layered on as a system prompt (`--append-system-prompt`), the agent **does call
WebSearch** before answering a "latest version this week" question. So grounding works.
- **Lesson:** to know which tools an agent used, parse `stream-json` `tool_use` events — not the final
  json `usage`. The bridge's `toolsUsed` now exposes only what json reliably gives (`numTurns`,
  `permissionDenials`); per-tool events are a Phase 2 / Agent SDK (stream-json) feature.
- **M1 research preset** (`AgentBridge.RESEARCH_PRESET`, sent via the bridge's new `appendSystemPrompt`
  field): verify time-sensitive/factual claims with WebSearch + cite sources; answer in a spoken style
  (no markdown/URLs) then a final `Sources:` line. Aligns with the SOUL truth charter — no guesses.

Also note for **M2 (coding):** `--allowedTools` is a **no-prompt allowlist, not a hard sandbox** — all
tools are still *offered* to the model; non-allowlisted ones that need permission are **auto-denied** in
headless mode (recorded in `permission_denials`). Safe for read-only M0/research; when M2 needs edits,
grant them explicitly per task and rely on git-diff review + confirm-before-commit.
