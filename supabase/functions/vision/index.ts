// POST /functions/v1/vision
// Body: { imageBase64, mediaType?, prompt? }  (a photo synced from the glasses)
// Claude multimodal describes / answers about the image. Returns { text }.
import { corsHeaders, json } from "../_shared/http.ts";
import { userClient } from "../_shared/supabaseClient.ts";

const ANTHROPIC_API_KEY = Deno.env.get("ANTHROPIC_API_KEY");
const MODEL = Deno.env.get("CLAUDE_MODEL") ?? "claude-sonnet-4-6";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  try {
    const { imageBase64, mediaType = "image/jpeg", prompt } = await req.json();
    if (!imageBase64) return json({ error: "imageBase64 is required" }, 400);

    const supabase = userClient(req);
    const { data: { user } } = await supabase.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);
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
