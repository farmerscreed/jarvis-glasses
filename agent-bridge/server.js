#!/usr/bin/env node
/*
 * JARVIS Agent Bridge — M0 (Phase 1, local synchronous)
 *
 * A tiny HTTP service on the director's PC that wraps headless Claude Code
 * (`claude -p --output-format json`) as the heavy "deliberate lane" engine.
 * JARVIS (the Android app) reaches it over the same `adb reverse` localhost
 * bridge used for the dev Supabase backend.
 *
 * Design: docs/AGENT_DELEGATION.md (M0). Zero npm deps — Node built-ins only.
 *
 * Security posture (M0):
 *   - Binds 127.0.0.1 ONLY (never the LAN). The phone reaches it via
 *     `adb reverse tcp:<port> tcp:<port>`, which forwards to PC localhost.
 *   - Shared bearer token (Authorization: Bearer <BRIDGE_TOKEN>).
 *   - Prompt is fed to claude via STDIN (never argv) — no shell, no escaping.
 *   - Least privilege: default allowedTools is read-only + web (research).
 *
 * Endpoints:
 *   GET  /health                      -> { ok, claudeBin, version? }
 *   POST /task {prompt, cwd?, allowedTools?, timeoutMs?}
 *        -> { id, status, result, summary, toolsUsed, durationMs, cost, sessionId, raw }
 */

'use strict';

const http = require('http');
const { spawn, execFileSync } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

// ----- minimal .env loader (no dependency) ---------------------------------
function loadDotEnv(file) {
  if (!fs.existsSync(file)) return;
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq < 0) continue;
    const key = line.slice(0, eq).trim();
    let val = line.slice(eq + 1).trim();
    if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) {
      val = val.slice(1, -1);
    }
    if (!(key in process.env)) process.env[key] = val;
  }
}
loadDotEnv(path.join(__dirname, '.env'));

// ----- config --------------------------------------------------------------
const PORT = parseInt(process.env.BRIDGE_PORT || '8765', 10);
// Bind host. Default 127.0.0.1 (localhost-only — reached via `adb reverse`). For untethered use over a
// Tailscale tunnel, set BRIDGE_HOST to the PC's tailnet IP (100.x.y.z) so only the private tailnet can
// reach it. A Cloudflare tunnel needs NO change (cloudflared connects to localhost). The bearer token
// guards it either way — do NOT set 0.0.0.0 on an untrusted network. See docs/SETUP_TUNNEL_AND_FCM.md.
const HOST = process.env.BRIDGE_HOST || '127.0.0.1';
const TOKEN = process.env.BRIDGE_TOKEN || '';
const REPO_ROOT = path.resolve(__dirname, '..');
const DEFAULT_CWD = process.env.BRIDGE_DEFAULT_CWD || REPO_ROOT;
const DEFAULT_TIMEOUT = 180000;     // 3 min
const MAX_TIMEOUT = 600000;         // 10 min
// Least-privilege research preset: read-only + web, no edits/shell.
const DEFAULT_ALLOWED = process.env.BRIDGE_DEFAULT_TOOLS || 'WebSearch,WebFetch,Read,Glob,Grep';
// Tool specs: built-in names (Read, Bash), MCP names (mcp__server__tool), and Claude Code
// permission patterns (Bash(git push:*)). Comma-separated. Args go to spawn WITHOUT a shell, so
// these characters carry no injection risk — the regex just rejects obviously-malformed input.
const ALLOWED_RE = /^[A-Za-z0-9_*().:,\- ]+$/;
const MAX_BODY = 1024 * 1024;       // 1 MB request cap
const AUDIT_FILE = path.join(__dirname, 'audit.log'); // JSONL, gitignored — every delegated task

function resolveClaude() {
  if (process.env.CLAUDE_BIN && fs.existsSync(process.env.CLAUDE_BIN)) return process.env.CLAUDE_BIN;
  // Try PATH resolution via `where` (Windows) / `which` (POSIX).
  try {
    const finder = process.platform === 'win32' ? 'where' : 'which';
    const out = execFileSync(finder, ['claude'], { encoding: 'utf8' });
    const first = out.split(/\r?\n/).map(s => s.trim()).filter(Boolean)[0];
    if (first && fs.existsSync(first)) return first;
  } catch (_) { /* fall through */ }
  return 'claude'; // last resort; spawn may still find it on PATH
}
const CLAUDE_BIN = resolveClaude();

// ----- helpers -------------------------------------------------------------
function json(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

function tokenOk(req) {
  if (!TOKEN) return true; // no token configured = open (localhost only) — warned at startup
  const hdr = req.headers['authorization'] || '';
  const m = /^Bearer\s+(.+)$/i.exec(hdr);
  if (!m) return false;
  const given = Buffer.from(m[1]);
  const want = Buffer.from(TOKEN);
  return given.length === want.length && crypto.timingSafeEqual(given, want);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) { reject(new Error('request body too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

// Append one task record to the audit log (trust/safety §6 — reviewable record of every delegation).
function audit(rec) {
  try { fs.appendFileSync(AUDIT_FILE, JSON.stringify(rec) + '\n'); } catch (_) { /* never fail a task on audit */ }
}

// In-memory ticket store for async tasks (M4). Tickets are ephemeral — they don't survive a bridge
// restart; the durable record is the app's Supabase `agent_tasks` table + the audit log.
const tasks = new Map();
const MAX_TICKETS = 200;

// Run one claude -p task. Resolves with a normalized result object.
function runTask({ id = crypto.randomUUID(), prompt, cwd, allowedTools, timeoutMs, appendSystemPrompt, disallowedTools }) {
  return new Promise((resolve) => {
    const started = Date.now();
    const startedIso = new Date().toISOString();
    const args = ['-p', '--output-format', 'json', '--allowedTools', allowedTools];
    if (disallowedTools) args.push('--disallowedTools', disallowedTools);
    // Optional preset instruction layered on as a SYSTEM prompt (e.g. the research preset:
    // "verify time-sensitive claims with WebSearch and cite sources"). Passed as a distinct argv
    // element — no shell, so spaces/quotes in the text are safe.
    if (appendSystemPrompt) args.push('--append-system-prompt', appendSystemPrompt);
    log(`[${id}] start cwd=${cwd} tools=${allowedTools} deny=${disallowedTools || '-'} timeout=${timeoutMs}ms sys=${appendSystemPrompt ? 'yes' : 'no'} prompt="${preview(prompt, 120)}"`);

    let child;
    try {
      child = spawn(CLAUDE_BIN, args, { cwd, windowsHide: true });
    } catch (e) {
      return resolve({ id, status: 'error', error: `spawn failed: ${e.message}`, durationMs: 0 });
    }

    let stdout = '';
    let stderr = '';
    let timedOut = false;
    child.stdout.on('data', (d) => { stdout += d; });
    child.stderr.on('data', (d) => { stderr += d; });

    const timer = setTimeout(() => {
      timedOut = true;
      try { child.kill('SIGKILL'); } catch (_) {}
    }, timeoutMs);

    child.on('error', (e) => {
      clearTimeout(timer);
      resolve({ id, status: 'error', error: `claude error: ${e.message}`, durationMs: Date.now() - started });
    });

    child.on('close', (code) => {
      clearTimeout(timer);
      const durationMs = Date.now() - started;
      if (timedOut) {
        log(`[${id}] TIMEOUT after ${durationMs}ms`);
        audit({ id, at: startedIso, status: 'timeout', durationMs, cwd, allowedTools, prompt: preview(prompt, 300) });
        return resolve({ id, status: 'timeout', error: `timed out after ${timeoutMs}ms`, durationMs, stderr: preview(stderr, 500) });
      }
      let parsed;
      try {
        parsed = JSON.parse(stdout);
      } catch (e) {
        log(`[${id}] non-JSON output (exit ${code})`);
        return resolve({
          id, status: 'error',
          error: `claude exited ${code}; output was not JSON`,
          durationMs, stdout: preview(stdout, 1000), stderr: preview(stderr, 1000),
        });
      }
      const isErr = parsed.is_error === true || parsed.subtype === 'error';
      const result = typeof parsed.result === 'string' ? parsed.result : '';
      // NOTE: `claude -p --output-format json` does NOT report which client tools (WebSearch/WebFetch/
      // Read/…) the agent actually used. `usage.server_tool_use` counts only API *server-side* tools
      // and stays ~0 in Claude Code even when WebSearch ran — so it is NOT a reliable "did it search"
      // signal (verified 2026-06-14). Real per-tool events need `--output-format stream-json`
      // (Phase 2 / Agent SDK). We surface only what json reliably gives: turns + permission denials.
      const su = (parsed.usage && parsed.usage.server_tool_use) || {};
      const out = {
        id,
        status: isErr ? 'error' : 'ok',
        result,
        summary: preview(result, 280),
        toolsUsed: {
          numTurns: parsed.num_turns,
          permissionDenials: Array.isArray(parsed.permission_denials)
            ? parsed.permission_denials.map(p => p.tool_name || p).filter(Boolean)
            : [],
          apiServerToolUse: su, // API server tools only; client WebSearch/WebFetch NOT counted here
        },
        durationMs: parsed.duration_ms != null ? parsed.duration_ms : durationMs,
        cost: parsed.total_cost_usd,
        sessionId: parsed.session_id,
        raw: parsed,
      };
      log(`[${id}] done status=${out.status} turns=${parsed.num_turns} denials=${out.toolsUsed.permissionDenials.length} ${durationMs}ms cost=$${out.cost}`);
      audit({ id, at: startedIso, status: out.status, durationMs, cwd, allowedTools, disallowedTools: disallowedTools || null,
        numTurns: parsed.num_turns, denials: out.toolsUsed.permissionDenials, cost: out.cost,
        prompt: preview(prompt, 300), result: preview(result, 600) });
      resolve(out);
    });

    child.stdin.on('error', () => {}); // ignore EPIPE if claude bails early
    child.stdin.write(prompt);
    child.stdin.end();
  });
}

function preview(s, n) {
  if (typeof s !== 'string') return '';
  const one = s.replace(/\s+/g, ' ').trim();
  return one.length > n ? one.slice(0, n) + '…' : one;
}
function log(msg) {
  const t = new Date().toISOString();
  console.log(`${t} ${msg}`);
}

// ----- server --------------------------------------------------------------
const handler = async (req, res) => {
  // CORS not needed (same-origin via adb reverse), but be lenient for local tooling.
  if (req.method === 'GET' && req.url === '/health') {
    return json(res, 200, { ok: true, service: 'jarvis-agent-bridge', version: 'M0-M4', claudeBin: CLAUDE_BIN });
  }

  if (req.method === 'POST' && req.url === '/task') {
    if (!tokenOk(req)) return json(res, 401, { status: 'error', error: 'unauthorized' });

    let bodyStr;
    try { bodyStr = await readBody(req); }
    catch (e) { return json(res, 413, { status: 'error', error: e.message }); }

    let body;
    try { body = bodyStr ? JSON.parse(bodyStr) : {}; }
    catch (_) { return json(res, 400, { status: 'error', error: 'invalid JSON body' }); }

    const prompt = body.prompt;
    if (typeof prompt !== 'string' || !prompt.trim()) {
      return json(res, 400, { status: 'error', error: 'prompt (non-empty string) is required' });
    }

    // cwd: optional; must exist + be a directory if provided.
    let cwd = DEFAULT_CWD;
    if (body.cwd != null) {
      const p = path.resolve(String(body.cwd));
      if (!fs.existsSync(p) || !fs.statSync(p).isDirectory()) {
        return json(res, 400, { status: 'error', error: `cwd does not exist or is not a directory: ${p}` });
      }
      cwd = p;
    }

    // allowedTools: optional; strict allowlist syntax. Default = research preset.
    let allowedTools = DEFAULT_ALLOWED;
    if (body.allowedTools != null && String(body.allowedTools).trim()) {
      const at = Array.isArray(body.allowedTools) ? body.allowedTools.join(',') : String(body.allowedTools).trim();
      if (!ALLOWED_RE.test(at)) {
        return json(res, 400, { status: 'error', error: 'allowedTools must be comma-separated tool names (e.g. "WebSearch,Read")' });
      }
      allowedTools = at;
    }

    // disallowedTools: optional defense-in-depth (e.g. block git push / rm in the coding lane).
    let disallowedTools = null;
    if (body.disallowedTools != null && String(body.disallowedTools).trim()) {
      const dt = Array.isArray(body.disallowedTools) ? body.disallowedTools.join(',') : String(body.disallowedTools).trim();
      if (!ALLOWED_RE.test(dt)) {
        return json(res, 400, { status: 'error', error: 'disallowedTools must be comma-separated tool names/patterns' });
      }
      disallowedTools = dt;
    }

    let timeoutMs = DEFAULT_TIMEOUT;
    if (body.timeoutMs != null) {
      const t = parseInt(body.timeoutMs, 10);
      if (Number.isFinite(t)) timeoutMs = Math.min(Math.max(t, 1000), MAX_TIMEOUT);
    }

    // appendSystemPrompt: optional preset/system instruction (capped).
    let appendSystemPrompt = null;
    if (body.appendSystemPrompt != null && String(body.appendSystemPrompt).trim()) {
      appendSystemPrompt = String(body.appendSystemPrompt).slice(0, 8000);
    }

    const params = { prompt, cwd, allowedTools, timeoutMs, appendSystemPrompt, disallowedTools };

    // Async (M4): return a ticket immediately, run in the background, poll via GET /task/:id.
    if (body.async === true) {
      const id = crypto.randomUUID();
      if (tasks.size >= MAX_TICKETS) tasks.delete(tasks.keys().next().value); // drop oldest
      tasks.set(id, { id, status: 'running', startedAt: new Date().toISOString() });
      runTask({ ...params, id })
        .then((out) => tasks.set(id, out))
        .catch((e) => tasks.set(id, { id, status: 'error', error: String(e && e.message || e) }));
      return json(res, 202, { id, status: 'running' });
    }

    const out = await runTask(params);
    const code = out.status === 'ok' ? 200 : (out.status === 'timeout' ? 504 : 502);
    return json(res, code, out);
  }

  // Async ticket status (M4). GET /task/:id
  if (req.method === 'GET' && req.url.startsWith('/task/')) {
    if (!tokenOk(req)) return json(res, 401, { status: 'error', error: 'unauthorized' });
    const id = req.url.slice('/task/'.length);
    const t = tasks.get(id);
    if (!t) return json(res, 404, { status: 'error', error: 'unknown task id' });
    return json(res, 200, t);
  }

  json(res, 404, { status: 'error', error: 'not found', endpoints: ['GET /health', 'POST /task', 'GET /task/:id'] });
};

// Bind 127.0.0.1 (dev, via `adb reverse`) AND, if set, the tunnel IP (e.g. the Tailscale tailnet IP
// for untethered prod). Explicitly NEVER 0.0.0.0 — only loopback + the private tailnet are exposed,
// and the bearer token still guards every /task. Each host gets its own Server sharing one handler.
const hosts = Array.from(new Set(['127.0.0.1', ...(HOST && HOST !== '127.0.0.1' && HOST !== '0.0.0.0' ? [HOST] : [])]));
if (HOST === '0.0.0.0') log('WARNING: BRIDGE_HOST=0.0.0.0 ignored (refusing all-interface bind); using 127.0.0.1 + tunnel IP only');
log(`JARVIS Agent Bridge (M0-M4)`);
log(`  claude binary : ${CLAUDE_BIN}`);
log(`  default cwd   : ${DEFAULT_CWD}`);
log(`  default tools : ${DEFAULT_ALLOWED}`);
log(`  auth token    : ${TOKEN ? 'configured' : 'NONE (set BRIDGE_TOKEN in agent-bridge/.env)'}`);
log(`  dev bridge    : adb reverse tcp:${PORT} tcp:${PORT}`);
hosts.forEach((h) => {
  http.createServer(handler).listen(PORT, h, () => log(`  listening on  : http://${h}:${PORT}`));
});
