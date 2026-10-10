// POST /functions/v1/vision
// Body: { imageBase64, mediaType?, prompt? }  (a photo synced from the glasses)
// Claude multimodal describes / answers about the image. Returns { text }.
import { corsHeaders, json } from "../_shared/http.ts";
import { userClient } from "../_shared/supabaseClient.ts";
import { checkRateLimit, HOURLY } from "../_shared/rateLimit.ts";

const ANTHROPIC_API_KEY = Deno.env.get("ANTHROPIC_API_KEY");
const MODEL = Deno.env.get("CLAUDE_MODEL") ?? "claude-sonnet-4-6";
const GEMINI_API_KEY = Deno.env.get("GEMINI_API_KEY");
// fast:true ("read / translate this"): Gemini Flash reads text in ~1–2 s vs ~5 s; falls back to Claude.
const FAST_MODEL = Deno.env.get("GEMINI_VISION_MODEL") ?? "gemini-2.5-flash";

async function geminiFast(imageBase64: string, mediaType: string, prompt: string): Promise<string | null> {
  if (!GEMINI_API_KEY) return null;
  const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${FAST_MODEL}:generateContent?key=${GEMINI_API_KEY}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      contents: [{ parts: [{ inline_data: { mime_type: mediaType, data: imageBase64 } }, { text: prompt }] }],
      generationConfig: { maxOutputTokens: 500, temperature: 0.2, thinkingConfig: { thinkingBudget: 0 } },
    }),
  });
  if (!res.ok) { console.error(`gemini vision ${res.status}: ${(await res.text()).slice(0, 300)}`); return null; }
  const data = await res.json();
  const text = (data.candidates?.[0]?.content?.parts ?? []).map((p: { text?: string }) => p.text ?? "").join("").trim();
  return text || null;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  try {
    const { imageBase64, mediaType = "image/jpeg", prompt, fast } = await req.json();
    if (!imageBase64) return json({ error: "imageBase64 is required" }, 400);

    const supabase = userClient(req);
    const { data: { user } } = await supabase.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);
    if (!await checkRateLimit(supabase, "vision", 80, HOURLY)) {
      return json({ error: "Hourly limit reached — try again shortly." }, 429);
    }
    if (fast) {
      const t0 = Date.now();
      const text = await geminiFast(imageBase64, mediaType, prompt ?? "Read the text in this photo aloud, in English.").catch(() => null);
      if (text) return json({ text: text.replace(/\*\*|__|`/g, ""), model: FAST_MODEL, ms: Date.now() - t0 });
    }
    if (!ANTHROPIC_API_KEY) return json({ error: "ANTHROPIC_API_KEY not set" }, 500);

    const res = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "x-api-key": ANTHROPIC_API_KEY,
        "anthropic-version": "2023-06-01",
        "content-type": "application/json",
      },
      body: JSON.stringify({
        model: MODEL,
        max_tokens: 400,
        messages: [{
          role: "user",
          content: [
            { type: "image", source: { type: "base64", media_type: mediaType, data: imageBase64 } },
            { type: "text", text: prompt ?? "You are JARVIS. Briefly say what's in this photo, in one or two natural spoken sentences." },
          ],
        }],
      }),
    });
    if (!res.ok) return json({ error: `anthropic ${res.status}: ${await res.text()}` }, 502);
    const data = await res.json();
    const text = (data.content ?? []).map((b: { text?: string }) => b.text ?? "").join("");
    return json({ text });
  } catch (e) {
    return json({ error: String(e) }, 500);
  }
});
