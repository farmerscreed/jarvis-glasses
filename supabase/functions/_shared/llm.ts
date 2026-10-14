// Phase 2 · M7 — the model switchboard. llmJson(job, …) tries the models listed for that job in the
// `model_routes` table (in order; editable with SQL, no deploy), skips providers whose key isn't set, and logs
// every attempt to `llm_calls` (tokens, $ estimate, ms, ok/error) for Settings → AI models & cost.
// Providers: anthropic (Claude), openrouter (one key → DeepSeek, Kimi, GLM, Qwen…; OpenAI-compatible), gemini.
import { SupabaseClient } from "npm:@supabase/supabase-js@2";
import { serviceClient } from "./supabaseClient.ts";

export type Provider = "anthropic" | "openrouter" | "gemini";
export type RouteEntry = { provider: Provider; model: string; max_tokens?: number; usd_in?: number; usd_out?: number };
type Usage = { tokensIn: number; tokensOut: number; usd?: number };

const KEYS: Record<Provider, string | undefined> = {
  anthropic: Deno.env.get("ANTHROPIC_API_KEY"),
  openrouter: Deno.env.get("OPENROUTER_API_KEY"),
  gemini: Deno.env.get("GEMINI_API_KEY"),
};
const CLAUDE_MODEL = Deno.env.get("CLAUDE_MODEL") ?? "claude-sonnet-4-6";

/** $ per million tokens [in, out] — ESTIMATES for the cost view when a route entry doesn't give its own. */
const PRICES: Record<string, [number, number]> = {
  "claude-sonnet-4-6": [3, 15],
  "claude-sonnet-5": [3, 15],
  "claude-sonnet-5-5": [3, 15],
  "claude-haiku-5-5": [1, 5],
  "gemini-2.5-flash": [0.3, 2.5],
  "gemini-2.5-flash-lite": [0.1, 0.4],
};

/** Used when a job has no row in model_routes (or the table can't be read). */
const FALLBACK: RouteEntry[] = [
  { provider: "anthropic", model: "default", max_tokens: 4000 },
  { provider: "gemini", model: "gemini-2.5-flash", max_tokens: 8000 },
];

const routeCache = new Map<string, { at: number; routes: RouteEntry[] }>();

/** The service client, or null where there is none (local tests without the service key). */
function safeDb(): SupabaseClient | null {
  try { return serviceClient(); } catch { return null; }
}

export async function routesFor(job: string, db: SupabaseClient | null = safeDb()): Promise<RouteEntry[]> {
  const hit = routeCache.get(job);
  if (hit && Date.now() - hit.at < 60_000) return hit.routes;
  if (!db) return FALLBACK;
  const { data } = await db.from("model_routes").select("routes").eq("job", job).maybeSingle().then((r) => r, () => ({ data: null }));
  const routes = Array.isArray(data?.routes) && data!.routes.length ? (data!.routes as RouteEntry[]) : FALLBACK;
  routeCache.set(job, { at: Date.now(), routes });
  return routes;
}

export function estimateUsd(model: string, tokensIn: number, tokensOut: number, entry?: Partial<RouteEntry>): number {
  const [pin, pout] = entry?.usd_in != null && entry?.usd_out != null ? [entry.usd_in, entry.usd_out] : (PRICES[model] ?? [3, 15]);
  return (tokensIn * pin + tokensOut * pout) / 1_000_000;
}

/** Record one AI call (also used by the meeting transcriber). Never throws. */
export async function logCall(row: {
  userId?: string | null; job: string; provider: string; model: string; tokensIn?: number; tokensOut?: number;
  usd?: number; ms: number; ok: boolean; error?: string;
}, db: SupabaseClient | null = safeDb()) {
  if (!db) return;
  try {
    await db.from("llm_calls").insert({
      user_id: row.userId ?? null, job: row.job, provider: row.provider, model: row.model,
      tokens_in: row.tokensIn ?? 0, tokens_out: row.tokensOut ?? 0, usd_est: row.usd ?? 0,
      ms: row.ms, ok: row.ok, error: row.error?.slice(0, 500) ?? null,
    });
  } catch (e) {
    console.warn(`llm_calls insert failed: ${e}`);
  }
}

async function callAnthropic(model: string, system: string, user: string, schema: Record<string, unknown>, maxTokens: number) {
  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: { "x-api-key": KEYS.anthropic!, "anthropic-version": "2023-06-01", "content-type": "application/json" },
    body: JSON.stringify({
      model, max_tokens: maxTokens, system, messages: [{ role: "user", content: user }],
      output_config: { format: { type: "json_schema", schema } },
    }),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`anthropic ${res.status}: ${text.slice(0, 300)}`);
  const data = JSON.parse(text);
  if (data.stop_reason === "refusal") throw new Error("Claude declined");
  const out = (data.content ?? []).find((b: { type: string }) => b.type === "text")?.text;
  if (!out) throw new Error(`no JSON from Claude (stop: ${data.stop_reason})`);
  return { out, usage: { tokensIn: data.usage?.input_tokens ?? 0, tokensOut: data.usage?.output_tokens ?? 0 } as Usage };
}

async function callOpenRouter(model: string, system: string, user: string, schema: Record<string, unknown>, maxTokens: number) {
  const res = await fetch("https://openrouter.ai/api/v1/chat/completions", {
    method: "POST",
    headers: { authorization: `Bearer ${KEYS.openrouter}`, "content-type": "application/json", "x-title": "JARVIS" },
    body: JSON.stringify({
      model, max_tokens: maxTokens, temperature: 0,
      messages: [{ role: "system", content: system }, { role: "user", content: user }],
      response_format: { type: "json_schema", json_schema: { name: "result", strict: true, schema } },
      usage: { include: true },
    }),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`openrouter ${res.status}: ${text.slice(0, 300)}`);
  const data = JSON.parse(text);
  if (data.error) throw new Error(`openrouter: ${JSON.stringify(data.error).slice(0, 300)}`);
  let out: string = data.choices?.[0]?.message?.content ?? "";
  out = out.trim().replace(/^```(?:json)?\s*/i, "").replace(/```\s*$/, ""); // some models wrap JSON in fences
  if (!out) throw new Error(`no content from ${model} (${data.choices?.[0]?.finish_reason ?? "?"})`);
  return {
    out,
    usage: { tokensIn: data.usage?.prompt_tokens ?? 0, tokensOut: data.usage?.completion_tokens ?? 0, usd: data.usage?.cost } as Usage,
  };
}

async function callGemini(model: string, system: string, user: string, schema: Record<string, unknown>, maxTokens: number) {
  const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-goog-api-key": KEYS.gemini! },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: system }] },
      contents: [{ role: "user", parts: [{ text: user }] }],
      generationConfig: {
        temperature: 0, maxOutputTokens: maxTokens, responseMimeType: "application/json",
        responseJsonSchema: schema, thinkingConfig: { thinkingBudget: 0 },
      },
    }),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`gemini ${res.status}: ${text.slice(0, 300)}`);
  const data = JSON.parse(text);
  const out = (data.candidates?.[0]?.content?.parts ?? []).map((p: { text?: string }) => p.text ?? "").join("");
  if (!out) throw new Error(`no JSON from ${model} (${data.candidates?.[0]?.finishReason ?? "?"})`);
  return {
    out,
    usage: { tokensIn: data.usageMetadata?.promptTokenCount ?? 0, tokensOut: data.usageMetadata?.candidatesTokenCount ?? 0 } as Usage,
  };
}

/**
 * One JSON answer for [job], trying its route in order. [schema] is a strict JSON schema (all properties
 * required, additionalProperties false). Throws only if every model on the route failed.
 */
export async function llmJson<T>(
  job: string,
  system: string,
  user: string,
  schema: Record<string, unknown>,
  opts: { userId?: string | null; routes?: RouteEntry[] } = {},
): Promise<T & { _model?: string }> {
  const db = safeDb();
  const routes = opts.routes ?? await routesFor(job, db);
  const errors: string[] = [];
  for (const r of routes) {
    if (!KEYS[r.provider]) continue; // e.g. OpenRouter before its key is set
    const model = r.provider === "anthropic" && (r.model === "default" || !r.model) ? CLAUDE_MODEL : r.model;
    const maxTokens = r.max_tokens ?? 4000;
    const t0 = Date.now();
    try {
      const fn = r.provider === "anthropic" ? callAnthropic : r.provider === "openrouter" ? callOpenRouter : callGemini;
      const { out, usage } = await fn(model, system, user, schema, maxTokens);
      const parsed = JSON.parse(out) as T & { _model?: string };
      const usd = usage.usd ?? estimateUsd(model, usage.tokensIn, usage.tokensOut, r);
      await logCall({ userId: opts.userId, job, provider: r.provider, model, tokensIn: usage.tokensIn, tokensOut: usage.tokensOut, usd, ms: Date.now() - t0, ok: true }, db);
      parsed._model = `${r.provider}/${model}`;
      return parsed;
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      errors.push(`${r.provider}/${model}: ${msg}`);
      console.warn(`llm ${job} ${r.provider}/${model} failed: ${msg.slice(0, 200)}`);
      await logCall({ userId: opts.userId, job, provider: r.provider, model, ms: Date.now() - t0, ok: false, error: msg }, db);
    }
  }
  throw new Error(`all models failed for ${job}: ${errors.join(" | ").slice(0, 600) || "no provider key set"}`);
}
