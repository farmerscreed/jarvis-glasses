// Text embeddings — provider-swappable. Default: Google Gemini (free tier).
// Output dimension MUST match the `memories.embedding vector(N)` column (currently 1536).
// gemini-embedding-001 supports configurable output dims via outputDimensionality.
const PROVIDER = (Deno.env.get("EMBED_PROVIDER") ?? "gemini").toLowerCase();
const DIM = Number(Deno.env.get("EMBED_DIM") ?? "1536");

export async function embed(text: string): Promise<number[]> {
  switch (PROVIDER) {
    case "gemini":
      return await embedGemini(text);
    case "openai":
      return await embedOpenAI(text);
    default:
      throw new Error(`unknown EMBED_PROVIDER: ${PROVIDER}`);
  }
}

async function embedGemini(text: string): Promise<number[]> {
  const key = Deno.env.get("GEMINI_API_KEY");
  if (!key) throw new Error("GEMINI_API_KEY is not set");
  const model = Deno.env.get("GEMINI_EMBED_MODEL") ?? "gemini-embedding-001";
  const res = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${model}:embedContent?key=${key}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        model: `models/${model}`,
        content: { parts: [{ text }] },
        outputDimensionality: DIM,
      }),
    },
  );
  if (!res.ok) throw new Error(`gemini embeddings ${res.status}: ${await res.text()}`);
  const data = await res.json();
  return data.embedding.values as number[];
}

async function embedOpenAI(text: string): Promise<number[]> {
  const key = Deno.env.get("OPENAI_API_KEY");
  if (!key) throw new Error("OPENAI_API_KEY is not set");
  const model = Deno.env.get("EMBED_MODEL") ?? "text-embedding-3-small";
  const res = await fetch("https://api.openai.com/v1/embeddings", {
    method: "POST",
    headers: { "Authorization": `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({ model, input: text }),
  });
  if (!res.ok) throw new Error(`openai embeddings ${res.status}: ${await res.text()}`);
  const data = await res.json();
  return data.data[0].embedding as number[];
}
