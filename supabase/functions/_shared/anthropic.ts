// Claude chat via the Anthropic Messages API (direct fetch — no SDK dependency).
const ANTHROPIC_API_KEY = Deno.env.get("ANTHROPIC_API_KEY");
const CLAUDE_MODEL = Deno.env.get("CLAUDE_MODEL") ?? "claude-sonnet-4-6";

export interface Msg {
  role: "user" | "assistant";
  content: string;
}

export async function claudeChat(
  messages: Msg[],
  system?: string,
  maxTokens = 1024,
): Promise<string> {
  if (!ANTHROPIC_API_KEY) throw new Error("ANTHROPIC_API_KEY is not set");
  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "x-api-key": ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
      "content-type": "application/json",
    },
    body: JSON.stringify({
      model: CLAUDE_MODEL,
      max_tokens: maxTokens,
      system,
      messages,
    }),
  });
  if (!res.ok) {
    throw new Error(`anthropic ${res.status}: ${await res.text()}`);
  }
  const data = await res.json();
  // content is an array of blocks; concatenate the text blocks.
  return (data.content ?? [])
    .map((b: { text?: string }) => b.text ?? "")
    .join("");
}

/**
 * One call that must return JSON matching [schema] (structured outputs, `output_config.format`).
 * [model] lets cheap jobs use a cheap model (e.g. FAST_MODEL for parsing a calendar request); if that
 * model is rejected (not on this key, retired…) it retries once on CLAUDE_MODEL so a config slip never
 * breaks the feature.
 */
export async function claudeJson<T>(
  system: string,
  user: string,
  schema: Record<string, unknown>,
  opts: { model?: string; maxTokens?: number; effort?: "low" | "medium" | "high" } = {},
): Promise<T> {
  if (!ANTHROPIC_API_KEY) throw new Error("ANTHROPIC_API_KEY is not set");
  const call = async (model: string) => {
    const res = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "x-api-key": ANTHROPIC_API_KEY!,
        "anthropic-version": "2023-06-01",
        "content-type": "application/json",
      },
      body: JSON.stringify({
        model,
        max_tokens: opts.maxTokens ?? 4000,
        system,
        messages: [{ role: "user", content: user }],
        output_config: {
          format: { type: "json_schema", schema },
          ...(opts.effort ? { effort: opts.effort } : {}),
        },
      }),
    });
    return { res, text: await res.text() };
  };
  const first = opts.model ?? CLAUDE_MODEL;
  let { res, text } = await call(first);
  if (!res.ok && first !== CLAUDE_MODEL && (res.status === 400 || res.status === 404)) {
    console.warn(`claudeJson: ${first} rejected (${res.status}) — retrying on ${CLAUDE_MODEL}: ${text.slice(0, 200)}`);
    ({ res, text } = await call(CLAUDE_MODEL));
  }
  if (!res.ok) throw new Error(`anthropic ${res.status}: ${text.slice(0, 300)}`);
  const data = JSON.parse(text);
  if (data.stop_reason === "refusal") throw new Error("Claude declined that request");
  const out = (data.content ?? []).find((b: { type: string }) => b.type === "text")?.text;
  if (!out) throw new Error(`no JSON in Claude's reply (stop: ${data.stop_reason})`);
  return JSON.parse(out) as T;
}

/** Cheap model for low-thinking jobs (parsing requests, short spoken summaries). Secret-overridable. */
export const FAST_MODEL = Deno.env.get("FAST_MODEL") ?? "claude-haiku-5-5";

/**
 * Streaming variant: invokes [onDelta] with each text chunk as Claude produces it (SSE), and
 * returns the full answer. Powers time-to-first-sentence TTS in the voice loop (Phase D).
 */
export async function claudeChatStream(
  messages: Msg[],
  system: string | undefined,
  onDelta: (text: string) => void,
  maxTokens = 1024,
): Promise<string> {
  if (!ANTHROPIC_API_KEY) throw new Error("ANTHROPIC_API_KEY is not set");
  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "x-api-key": ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
      "content-type": "application/json",
    },
    body: JSON.stringify({ model: CLAUDE_MODEL, max_tokens: maxTokens, system, messages, stream: true }),
  });
  if (!res.ok || !res.body) throw new Error(`anthropic ${res.status}: ${await res.text()}`);

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let full = "";
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split("\n");
    buffer = lines.pop() ?? ""; // keep the partial last line
    for (const line of lines) {
      const t = line.trim();
      if (!t.startsWith("data:")) continue;
      const payload = t.slice(5).trim();
      if (payload === "[DONE]") continue;
      try {
        const evt = JSON.parse(payload);
        if (evt.type === "content_block_delta" && evt.delta?.type === "text_delta") {
          full += evt.delta.text;
          onDelta(evt.delta.text);
        }
      } catch (_) { /* ignore keep-alives / non-JSON */ }
    }
  }
  return full;
}
