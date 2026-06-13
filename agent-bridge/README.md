# JARVIS Agent Bridge (M0)

The local entry point to JARVIS's **deliberate lane**: a tiny Node HTTP service that
wraps **headless Claude Code** (`claude -p --output-format json`) running on the
director's **Max subscription**, so JARVIS can delegate heavy, multi-step, tool-using
tasks (research → coding → email/calendar). Design: [`../docs/AGENT_DELEGATION.md`](../docs/AGENT_DELEGATION.md).

This is **M0** (Phase 1, local + synchronous): no app changes, just the bridge,
verified by `curl`. M1 wires the Android app to it over `adb reverse`.

```
glasses/voice → JARVIS app → (HTTP) → Agent Bridge (this) → claude -p (Max sub, tools) → result → spoken summary
```

## Why this is allowed on the subscription
Running **Claude Code the product** (logged in via `claude login` / Max sub) as a
delegated agent is legitimate and intended — distinct from using subscription
credentials to make raw Anthropic API calls (still off-limits; the fast voice lane
stays on the API). See `AGENT_DELEGATION.md` §1.

On this machine `claude` is authed via the **subscription** (no `ANTHROPIC_API_KEY`
in env). The JSON `total_cost_usd` field is the *reported equivalent*, not an API charge.

## Zero dependencies
Built on Node built-ins only (`http`, `child_process`). No `npm install` needed.
Requires Node ≥ 18 and `claude` on `PATH` (auto-detected; override with `CLAUDE_BIN`).

## Run

```powershell
# from repo root
node agent-bridge\server.js
# or
cd agent-bridge ; npm start
```

Config lives in `agent-bridge/.env` (gitignored — copy from `.env.example`).
A `.env` with a random `BRIDGE_TOKEN` and `BRIDGE_PORT=8765` is already created locally.

On startup it prints the listen address, resolved `claude` binary, default cwd/tools,
and whether a token is configured.

## Security posture (M0)
- **Binds `127.0.0.1` only** — never the LAN. The phone reaches it through
  `adb reverse tcp:8765 tcp:8765` (the same localhost bridge used for dev Supabase).
- **Shared bearer token** (`Authorization: Bearer <BRIDGE_TOKEN>`). If `BRIDGE_TOKEN`
  is empty the bridge runs open (acceptable only because it's localhost-only).
- **Prompt is sent via stdin**, never as a shell arg — no shell, no injection.
- **Least privilege:** the default `allowedTools` is read-only + web (research preset:
  `WebSearch,WebFetch,Read,Glob,Grep`). Coding/email presets come in M2/M3 with
  confirm-before-act gates.

## API

### `GET /health`
```json
{ "ok": true, "service": "jarvis-agent-bridge", "version": "M0", "claudeBin": "…" }
```

### `POST /task`
Request:
```json
{
  "prompt": "Research X and summarize with sources.",
  "cwd": "C:/path/to/repo",          // optional, defaults to repo root
  "allowedTools": "WebSearch,WebFetch,Read",  // optional, defaults to research preset
  "disallowedTools": "Bash(git push:*),Bash(rm:*)", // optional, defense-in-depth (--disallowedTools)
  "appendSystemPrompt": "…preset…",  // optional, layered as a system prompt (--append-system-prompt)
  "timeoutMs": 180000,                // optional, default 180000, max 600000
  "async": false                      // optional (M4): true => returns a ticket, poll GET /task/:id
}
```
Response (`200` ok / `502` error / `504` timeout):
```json
{
  "id": "uuid",
  "status": "ok",
  "result": "…the full answer…",
  "summary": "…first ~280 chars…",
  "toolsUsed": { "webSearch": 3, "webFetch": 1, "permissionDenials": [], "numTurns": 5 },
  "durationMs": 41234,
  "cost": 0.12,
  "sessionId": "…",
  "raw": { /* full claude -p JSON */ }
}
```

## Test with curl

```bash
TOKEN=$(grep BRIDGE_TOKEN agent-bridge/.env | cut -d= -f2)

# health
curl -s http://127.0.0.1:8765/health

# a research task
curl -s -X POST http://127.0.0.1:8765/task \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Research the current Anthropic Claude model lineup and pricing. Give a short sourced summary.","timeoutMs":240000}'
```

### `GET /task/:id` (M4 async)
Poll the status of an `async` task: `{ id, status: "running" | "ok" | "error" | "timeout", result?, … }`.
Tickets are in-memory (don't survive a bridge restart); the durable record is the app's Supabase
`agent_tasks` table + `audit.log`.

## Audit log
Every delegated task is appended to `agent-bridge/audit.log` (JSONL, gitignored): time, status,
duration, tools, prompt/result previews, permission denials. The reviewable record of what the
agent did on the director's behalf (trust/safety §6).

## From the phone (M1 preview)
```
adb reverse tcp:8765 tcp:8765
# app POSTs to http://127.0.0.1:8765/task with the bearer token
```

## Roadmap
- **M0 (this):** bridge + `POST /task`, curl-verified research. ✅
- **M1:** app dispatches "Jarvis, research…" → bridge → spoken summary → distilled to memory.
- **M2:** coding preset (repo cwd + edit/run + git-diff review + confirm-before-commit).
- **M3:** email/calendar via MCP + confirm-before-send.
- **M4:** Phase 2 — async tickets (`async` + `GET /task/:id`) ✅ local slice; `agent_tasks` store
  (migration, local) ✅; **still to do:** app wiring (write/poll `agent_tasks`), FCM push, hosted env,
  Agent SDK streaming.
