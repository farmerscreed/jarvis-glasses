// POST /functions/v1/recall
// Body: { query, limit?, threshold?, type? }
// Embeds `query` and returns the user's most semantically similar memories (match_memories RPC).
import { corsHeaders, json } from "../_shared/http.ts";
import { userClient } from "../_shared/supabaseClient.ts";
import { embed } from "../_shared/embeddings.ts";
import { checkRateLimit, HOURLY } from "../_shared/rateLimit.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  try {
    const { query, limit = 10, threshold = 0.0, type = null } = await req.json();
    if (!query) return json({ error: "query is required" }, 400);

    const supabase = userClient(req);
    if (!await checkRateLimit(supabase, "recall", 300, HOURLY)) {
      return json({ error: "Hourly limit reached — try again shortly." }, 429);
    }
    const embedding = await embed(query);

    const { data, error } = await supabase.rpc("match_memories", {
      query_embedding: embedding,
      match_count: limit,
      match_threshold: threshold,
      filter_type: type,
    });

    if (error) return json({ error: error.message }, 400);
    return json({ matches: data });
  } catch (e) {
    return json({ error: String(e) }, 500);
  }
});
