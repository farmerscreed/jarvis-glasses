// POST /functions/v1/ingest
// Body: { text, type?, media_path?, tags?, metadata?, lat?, lng? }
// Embeds `text` and stores a memory for the signed-in user (RLS-enforced).
import { corsHeaders, json } from "../_shared/http.ts";
import { userClient } from "../_shared/supabaseClient.ts";
import { embed } from "../_shared/embeddings.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  try {
    const body = await req.json();
    const text: string = body.text;
    if (!text) return json({ error: "text is required" }, 400);

    const supabase = userClient(req);
    const { data: { user } } = await supabase.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);

    const embedding = await embed(text);

    const { data, error } = await supabase
      .from("memories")
      .insert({
        user_id: user.id,
        type: body.type ?? "note",
        text,
        embedding,
        media_path: body.media_path ?? null,
        lat: body.lat ?? null,
        lng: body.lng ?? null,
        tags: body.tags ?? [],
        metadata: body.metadata ?? {},
      })
      .select("id, created_at, type, text, media_path, tags, metadata")
      .single();

    if (error) return json({ error: error.message }, 400);
    return json({ memory: data });
  } catch (e) {
    return json({ error: String(e) }, 500);
  }
});
