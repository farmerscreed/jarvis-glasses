// GET  /functions/v1/profile            -> { soul, user_facts }
// POST /functions/v1/profile {soul?, user_facts?} -> upsert provided fields, returns the updated profile
// The always-on assistant profile: JARVIS's character (soul) + curated facts about the director.
import { corsHeaders, json } from "../_shared/http.ts";
import { userClient } from "../_shared/supabaseClient.ts";
import { getProfile } from "../_shared/profile.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  try {
    const supabase = userClient(req);
    const { data: { user } } = await supabase.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);

    if (req.method === "GET") {
      return json(await getProfile(supabase, user.id));
    }

    // POST/PUT: update only the provided fields.
    const body = await req.json().catch(() => ({}));
    const update: Record<string, unknown> = { user_id: user.id, updated_at: new Date().toISOString() };
    if (typeof body.soul === "string") update.soul = body.soul;
    if (typeof body.user_facts === "string") update.user_facts = body.user_facts;
    const { error } = await supabase.from("profile").upsert(update, { onConflict: "user_id" });
    if (error) return json({ error: error.message }, 500);
    return json(await getProfile(supabase, user.id));
  } catch (e) {
    return json({ error: String(e) }, 500);
  }
});
