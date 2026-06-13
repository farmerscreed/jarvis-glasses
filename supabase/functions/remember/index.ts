// POST /functions/v1/remember { text }
// Assistant memory v1.3 — the explicit "remember that…" command. Pins a fact into the user's profile
// immediately (no waiting for end-of-conversation distillation), so an explicit ask is never lost.
// Strips the command prefix server-side and appends a clean bullet to profile.user_facts.
import { corsHeaders, json } from "../_shared/http.ts";
import { userClient } from "../_shared/supabaseClient.ts";
import { getProfile } from "../_shared/profile.ts";
import { checkRateLimit, HOURLY } from "../_shared/rateLimit.ts";

const PREFIX = /^\s*(please\s+)?(jarvis[,!.]?\s+)?(remember|note|don'?t\s+forget|make\s+a\s+note)\s*(that|to|:)?\s*/i;

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  try {
    const supabase = userClient(req);
    const { data: { user } } = await supabase.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);
    if (!await checkRateLimit(supabase, "remember", 120, HOURLY)) {
      return json({ error: "Hourly limit reached — try again shortly." }, 429);
    }

    const { text } = await req.json();
    const fact = String(text ?? "").replace(PREFIX, "").trim().replace(/[.\s]+$/, "");
    if (!fact) return json({ error: "nothing to remember" }, 400);

    const current = (await getProfile(supabase, user.id)).user_facts;
    const updated = (current ? current + "\n" : "") + "- " + fact;
    const { error } = await supabase.from("profile").upsert(
      { user_id: user.id, user_facts: updated, updated_at: new Date().toISOString() },
      { onConflict: "user_id" },
    );
    if (error) return json({ error: error.message }, 500);
    return json({ ok: true, fact });
  } catch (e) {
    return json({ error: String(e) }, 500);
  }
});
