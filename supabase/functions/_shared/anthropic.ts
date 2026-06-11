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
