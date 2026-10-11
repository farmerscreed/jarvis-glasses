import { createClient, SupabaseClient } from "npm:@supabase/supabase-js@2";

// A service-role client — bypasses RLS. ONLY for server-held data the app must never read itself
// (google_connections, pending_actions); always filter by the verified user's id.
export function serviceClient(): SupabaseClient {
  return createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false },
  });
}

// A Supabase client scoped to the calling user's JWT, so Row Level Security applies
// (recall/ingest run as the signed-in user). SUPABASE_URL / SUPABASE_ANON_KEY are
// injected automatically by the platform (locally and in the cloud).
export function userClient(req: Request): SupabaseClient {
  const url = Deno.env.get("SUPABASE_URL")!;
  const anon = Deno.env.get("SUPABASE_ANON_KEY")!;
  const authHeader = req.headers.get("Authorization") ?? "";
  return createClient(url, anon, {
    global: { headers: { Authorization: authHeader } },
    auth: { persistSession: false },
  });
}
