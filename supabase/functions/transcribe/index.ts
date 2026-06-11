// POST /functions/v1/transcribe
// Body: { audioBase64, mimeType? }  (audio is a WAV clip from the glasses mic)
// Uses Gemini multimodal (free tier) to transcribe, with retry + model fallback for transient 503/429.
import { corsHeaders, json } from "../_shared/http.ts";
import { userClient } from "../_shared/supabaseClient.ts";

const GEMINI_API_KEY = Deno.env.get("GEMINI_API_KEY");
const PRIMARY = Deno.env.get("GEMINI_STT_MODEL") ?? "gemini-2.5-flash";
// Fallback chain (dedup); flash-lite is a lighter model that shares the free quota.
const MODELS = [...new Set([PRIMARY, "gemini-2.5-flash-lite"])];

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function transcribeWith(model: string, audioBase64: string, mimeType: string): Promise<Response> {
  return await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${GEMINI_API_KEY}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        contents: [{
          parts: [
            { text: "Transcribe this audio to text verbatim. Return ONLY the transcript with no preamble, quotes, or commentary." },
            { inline_data: { mime_type: mimeType, data: audioBase64 } },
          ],
        }],
      }),
    },
  );
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  try {
    const { audioBase64, mimeType = "audio/wav" } = await req.json();
    if (!audioBase64) return json({ error: "audioBase64 is required" }, 400);

    const supabase = userClient(req);
    const { data: { user } } = await supabase.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);
    if (!GEMINI_API_KEY) return json({ error: "GEMINI_API_KEY not set" }, 500);

    let lastErr = "";
    for (const model of MODELS) {
      for (let attempt = 0; attempt < 3; attempt++) {
        const res = await transcribeWith(model, audioBase64, mimeType);
        if (res.ok) {
          const data = await res.json();
          const text = (data.candidates?.[0]?.content?.parts ?? [])
            .map((p: { text?: string }) => p.text ?? "")
            .join("")
            .trim();
          return json({ text, model });
        }
        lastErr = `${model} ${res.status}: ${await res.text()}`;
        // 503 (overloaded) / 429 (rate) are transient: back off and retry, then fall through to next model.
        if (res.status === 503 || res.status === 429) {
          await sleep(700 * (attempt + 1));
          continue;
        }
        break; // hard error for this model -> try next model
      }
    }
    return json({ error: `gemini stt failed: ${lastErr}` }, 502);
  } catch (e) {
    return json({ error: String(e) }, 500);
  }
});
