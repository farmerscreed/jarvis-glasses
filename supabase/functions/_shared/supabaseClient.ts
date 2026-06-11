import { createClient, SupabaseClient } from "npm:@supabase/supabase-js@2";

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
