import { SupabaseClient } from "npm:@supabase/supabase-js@2";

/**
 * Per-user fixed-window rate limit, backed by the check_rate_limit Postgres RPC. Returns true if
 * the call is allowed. Fails OPEN on an infra error (we never want a rate-limit hiccup to lock the
 * user out of their own assistant — the cap is a budget guardrail, not a security boundary).
 */
export async function checkRateLimit(
  supabase: SupabaseClient,
  fn: string,
  limit: number,
  windowSeconds: number,
): Promise<boolean> {
  const { data, error } = await supabase.rpc("check_rate_limit", {
    p_fn: fn,
    p_limit: limit,
    p_window_seconds: windowSeconds,
  });
  if (error) {
    console.error(`rate_limit ${fn} error: ${error.message}`);
    return true; // fail open
  }
  return data === true;
}

/** Generous per-hour caps — high enough for real use, low enough to stop a runaway loop. */
export const HOURLY = 3600;
