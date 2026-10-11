// POST /functions/v1/google-connect
//   { op: "status" }                       → { configured, connected, email?, scopes? }
//   { op: "connect", serverAuthCode }      → { connected: true, email, scopes }
//   { op: "disconnect" }                   → { connected: false }
// Links the user's Google account (Gmail drafts, Calendar, Contacts) to JARVIS. The phone sends the
// one-time code from Google's consent screen; the refresh token is kept here, encrypted, never on
// the phone. See _shared/google.ts.
import { corsHeaders, json } from "../_shared/http.ts";
import { userClient } from "../_shared/supabaseClient.ts";
import { checkRateLimit, HOURLY } from "../_shared/rateLimit.ts";
import { connectionStatus, connectWithCode, disconnect, GOOGLE_SCOPES, googleConfigured } from "../_shared/google.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  try {
    const supabase = userClient(req);
    const { data: { user } } = await supabase.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);
    if (!await checkRateLimit(supabase, "google-connect", 30, HOURLY)) {
      return json({ error: "Too many tries — wait a few minutes." }, 429);
    }
    const { op, serverAuthCode } = await req.json();

    if (op === "status") {
      if (!googleConfigured()) return json({ configured: false, connected: false, scopes: GOOGLE_SCOPES });
      return json({ configured: true, ...(await connectionStatus(user.id)), wantScopes: GOOGLE_SCOPES });
    }
    if (!googleConfigured()) return json({ error: "Google isn't set up on the server yet." }, 503);

    if (op === "connect") {
      const code = String(serverAuthCode ?? "").trim();
      if (!code) return json({ error: "serverAuthCode is required" }, 400);
      const { email, scopes } = await connectWithCode(user.id, code);
      const missing = GOOGLE_SCOPES.filter((s) => s !== "email" && !scopes.includes(s));
      return json({ connected: true, email, scopes, missing });
    }
    if (op === "disconnect") {
      await disconnect(user.id);
      return json({ connected: false });
    }
    return json({ error: "op must be status | connect | disconnect" }, 400);
  } catch (e) {
    console.error("google-connect error:", e);
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
