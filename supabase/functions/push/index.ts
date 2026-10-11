// POST /functions/v1/push
//   { op: "register", token, appId? }  → { ok }        this phone can now be notified
//   { op: "unregister", token }        → { ok }        e.g. on sign-out
//   { op: "test" }                     → { sent }      "JARVIS can reach you" to every registered phone
//   { op: "status" }                   → { configured, devices }
// Sending from other features: import { sendPush } from "../_shared/fcm.ts".
import { corsHeaders, json } from "../_shared/http.ts";
import { serviceClient, userClient } from "../_shared/supabaseClient.ts";
import { checkRateLimit, HOURLY } from "../_shared/rateLimit.ts";
import { pushConfigured, sendPush } from "../_shared/fcm.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  try {
    const supabase = userClient(req);
    const { data: { user } } = await supabase.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);
    if (!await checkRateLimit(supabase, "push", 60, HOURLY)) return json({ error: "Too many requests." }, 429);
    const { op, token, appId } = await req.json();
    const db = serviceClient();

    switch (op) {
      case "register": {
        const t = String(token ?? "").trim();
        if (t.length < 20) return json({ error: "token is required" }, 400);
        const { error } = await db.from("push_tokens").upsert({
          token: t,
          user_id: user.id,
          app_id: String(appId ?? ""),
          updated_at: new Date().toISOString(),
        });
        if (error) return json({ error: error.message }, 500);
        return json({ ok: true });
      }
      case "unregister": {
        await db.from("push_tokens").delete().eq("token", String(token ?? "")).eq("user_id", user.id);
        return json({ ok: true });
      }
      case "status": {
        const { count } = await db.from("push_tokens").select("token", { count: "exact", head: true }).eq("user_id", user.id);
        return json({ configured: pushConfigured(), devices: count ?? 0 });
      }
      case "test": {
        if (!pushConfigured()) return json({ error: "Push isn't set up on the server yet." }, 503);
        const sent = await sendPush(user.id, "JARVIS", "Notifications are working. I can reach you now, even with the app closed.", { kind: "test" });
        return json({ sent });
      }
      default:
        return json({ error: "op must be register | unregister | status | test" }, 400);
    }
  } catch (e) {
    console.error("push error:", e);
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
