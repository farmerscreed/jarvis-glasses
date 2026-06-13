// POST /functions/v1/ingest
// Body: { text, type?, media_path?, tags?, metadata?, lat?, lng?, client_id? }
// Embeds `text` and stores a memory for the signed-in user (RLS-enforced).
// `client_id` makes the write idempotent: re-sending the same one returns the existing row
// (the offline-first outbox retries the same client_id until it confirms a server id).
import { corsHeaders, json } from "../_shared/http.ts";
import { userClient } from "../_shared/supabaseClient.ts";
import { checkRateLimit, HOURLY } from "../_shared/rateLimit.ts";
import { embed } from "../_shared/embeddings.ts";

const SELECT = "id, created_at, type, text, media_path, tags, metadata, client_id";

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
    if (!await checkRateLimit(supabase, "ingest", 300, HOURLY)) {
      return json({ error: "Hourly limit reached — try again shortly." }, 429);
    }

    // Idempotency: if this client_id already landed, return it without re-embedding (saves a
    // paid embed call on every retry) — the outbox just needed the server id confirmed.
    const clientId: string | null = body.client_id ?? null;
    if (clientId) {
      const { data: existing } = await supabase
        .from("memories")
        .select(SELECT)
        .eq("client_id", clientId)
        .maybeSingle();
      if (existing) return json({ memory: existing, deduped: true });
    }

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
        client_id: clientId,
      })
      .select(SELECT)
      .single();

    // A concurrent retry may have inserted the same client_id between our check and insert →
    // unique-violation 23505. Fetch and return the winner instead of erroring.
    if (error) {
      if (clientId && error.code === "23505") {
        const { data: raced } = await supabase
          .from("memories").select(SELECT).eq("client_id", clientId).maybeSingle();
        if (raced) return json({ memory: raced, deduped: true });
      }
      return json({ error: error.message }, 400);
    }
    return json({ memory: data });
  } catch (e) {
    return json({ error: String(e) }, 500);
  }
});
