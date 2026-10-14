// Google access for the calendar / email functions (Phase 1, 2026-10-11).
//
// The app gets a one-time server auth code from Google (Android AuthorizationClient with offline
// access); `google-connect` swaps it here for a long-lived refresh token, stored encrypted in
// google_connections. Every call then mints a short-lived access token from it. The phone never
// holds the refresh token.
//
// Secrets: GOOGLE_CLIENT_ID + GOOGLE_CLIENT_SECRET (the OAuth **Web** client), GOOGLE_TOKEN_KEY.
import { serviceClient } from "./supabaseClient.ts";
import { decryptSecret, encryptSecret } from "./crypto.ts";

const CLIENT_ID = Deno.env.get("GOOGLE_CLIENT_ID") ?? "";
const CLIENT_SECRET = Deno.env.get("GOOGLE_CLIENT_SECRET") ?? "";

/** What the app asks for. Drafts only (compose), calendar events, contacts lookup, and Drive files JARVIS creates. */
export const GOOGLE_SCOPES = [
  "https://www.googleapis.com/auth/gmail.compose",
  "https://www.googleapis.com/auth/calendar.events",
  "https://www.googleapis.com/auth/contacts.readonly",
  "https://www.googleapis.com/auth/contacts.other.readonly",
  "https://www.googleapis.com/auth/drive.file", // M6b: only files JARVIS creates (starred meetings)
  "email",
];

/** The user hasn't connected Google, or Google revoked/expired the grant. Spoken as-is. */
export class GoogleNotConnected extends Error {
  constructor(msg = "Your Google account isn't connected. Open Settings and tap Connect Gmail & Calendar.") {
    super(msg);
  }
}

export function googleConfigured(): boolean {
  return CLIENT_ID !== "" && CLIENT_SECRET !== "" && !!Deno.env.get("GOOGLE_TOKEN_KEY");
}

async function tokenRequest(params: Record<string, string>) {
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ client_id: CLIENT_ID, client_secret: CLIENT_SECRET, ...params }),
  });
  const body = await res.json().catch(() => ({}));
  return { ok: res.ok, status: res.status, body };
}

/** Email inside a Google id_token (straight from Google's token endpoint over TLS — not re-verified). */
function emailFromIdToken(idToken?: string): string {
  if (!idToken) return "";
  try {
    const payload = idToken.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    return JSON.parse(atob(payload)).email ?? "";
  } catch (_) {
    return "";
  }
}

/** Swap the app's one-time server auth code for tokens and store the refresh token (encrypted). */
export async function connectWithCode(userId: string, serverAuthCode: string) {
  // Android server auth codes are exchanged with an empty redirect_uri.
  const r = await tokenRequest({ code: serverAuthCode, grant_type: "authorization_code", redirect_uri: "" });
  if (!r.ok) throw new Error(`Google refused the sign-in code (${r.body?.error ?? r.status})`);
  const refresh = r.body.refresh_token as string | undefined;
  if (!refresh) {
    // Google only returns a refresh token on first consent; a repeat connect without it is useless.
    throw new Error("Google didn't grant offline access — disconnect JARVIS in your Google account settings, then connect again.");
  }
  const email = emailFromIdToken(r.body.id_token);
  const scopes = String(r.body.scope ?? "").split(" ").filter(Boolean);
  const { error } = await serviceClient().from("google_connections").upsert({
    user_id: userId,
    google_email: email,
    refresh_token_enc: await encryptSecret(refresh),
    scopes,
    status: "ok",
    updated_at: new Date().toISOString(),
  });
  if (error) throw new Error(`couldn't save the Google connection: ${error.message}`);
  cache.set(userId, { token: r.body.access_token, until: Date.now() + (r.body.expires_in ?? 3000) * 1000 - 60_000 });
  return { email, scopes };
}

export async function connectionStatus(userId: string) {
  const { data } = await serviceClient().from("google_connections")
    .select("google_email, scopes, status, updated_at").eq("user_id", userId).maybeSingle();
  if (!data) return { connected: false };
  return { connected: data.status === "ok", email: data.google_email, scopes: data.scopes, status: data.status };
}

/** Revoke at Google (best effort) and forget the token. */
export async function disconnect(userId: string) {
  const db = serviceClient();
  const { data } = await db.from("google_connections").select("refresh_token_enc").eq("user_id", userId).maybeSingle();
  if (data) {
    try {
      const token = await decryptSecret(data.refresh_token_enc);
      await fetch("https://oauth2.googleapis.com/revoke", {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ token }),
      });
    } catch (_) { /* revoke is best effort — the row is deleted either way */ }
  }
  cache.delete(userId);
  await db.from("google_connections").delete().eq("user_id", userId);
}

// Access tokens live ~1 h; keep one per user for the life of this function instance.
const cache = new Map<string, { token: string; until: number }>();

export async function accessToken(userId: string): Promise<string> {
  const hit = cache.get(userId);
  if (hit && hit.until > Date.now()) return hit.token;
  const db = serviceClient();
  const { data } = await db.from("google_connections")
    .select("refresh_token_enc, status").eq("user_id", userId).maybeSingle();
  if (!data) throw new GoogleNotConnected();
  if (data.status !== "ok") {
    throw new GoogleNotConnected("Your Google connection needs renewing. Open Settings and tap Connect Gmail & Calendar.");
  }
  const r = await tokenRequest({ refresh_token: await decryptSecret(data.refresh_token_enc), grant_type: "refresh_token" });
  if (!r.ok) {
    if (r.body?.error === "invalid_grant") {
      await db.from("google_connections").update({ status: "needs_reconnect", updated_at: new Date().toISOString() })
        .eq("user_id", userId);
      throw new GoogleNotConnected("Your Google connection has expired. Open Settings and tap Connect Gmail & Calendar.");
    }
    throw new Error(`Google token refresh failed (${r.body?.error ?? r.status})`);
  }
  cache.set(userId, { token: r.body.access_token, until: Date.now() + (r.body.expires_in ?? 3000) * 1000 - 60_000 });
  return r.body.access_token;
}

/** Call a Google API as the user. Throws with Google's message on failure. */
// deno-lint-ignore no-explicit-any
export async function googleApi(userId: string, url: string, init: RequestInit = {}): Promise<any> {
  const token = await accessToken(userId);
  const res = await fetch(url, {
    ...init,
    headers: { ...(init.headers ?? {}), authorization: `Bearer ${token}`, "content-type": "application/json" },
  });
  const text = await res.text();
  // deno-lint-ignore no-explicit-any
  let body: any = {};
  try { body = text ? JSON.parse(text) : {}; } catch (_) { body = { raw: text }; }
  if (res.status === 401) { cache.delete(userId); throw new GoogleNotConnected("Google didn't accept the connection. Open Settings and connect Gmail & Calendar again."); }
  if (res.status === 403) {
    const msg = String(body?.error?.message ?? "");
    // "Gmail API has not been used in project … or it is disabled" — a console setup step, not the user.
    if (/has not been used|is disabled|accessNotConfigured/i.test(msg + JSON.stringify(body?.error?.errors ?? ""))) {
      throw new Error(`A Google API isn't switched on in the Google Cloud project: ${msg.slice(0, 160)}`);
    }
    throw new GoogleNotConnected("JARVIS doesn't have permission for that in your Google account. Open Settings and connect Gmail & Calendar again.");
  }
  if (!res.ok) throw new Error(`Google API ${res.status}: ${body?.error?.message ?? text.slice(0, 200)}`);
  return body;
}

// ── time zones (no library: Intl gives us the offset) ────────────────────────────────────────────
/** "+01:00" for [tz] at instant [at]. */
export function tzOffset(tz: string, at = new Date()): string {
  const name = new Intl.DateTimeFormat("en-US", { timeZone: tz, timeZoneName: "longOffset" })
    .formatToParts(at).find((p) => p.type === "timeZoneName")?.value ?? "GMT";
  const m = name.match(/GMT([+-]\d{2}):?(\d{2})?/);
  return m ? `${m[1]}:${m[2] ?? "00"}` : "+00:00";
}

/** Local wall-clock "YYYY-MM-DDTHH:MM" in [tz] → RFC 3339 with that zone's offset. */
export function localToRfc3339(local: string, tz: string): string {
  const base = local.length === 10 ? `${local}T00:00` : local.slice(0, 16);
  // Offset at (approximately) that moment — correct across DST except within the switch hour.
  const approx = new Date(`${base}:00Z`);
  return `${base}:00${tzOffset(tz, approx)}`;
}

/** "Saturday 11 October 2026, 16:05" — the current local time in [tz], for prompts. */
export function nowIn(tz: string): string {
  return new Intl.DateTimeFormat("en-GB", {
    timeZone: tz, weekday: "long", day: "numeric", month: "long", year: "numeric",
    hour: "2-digit", minute: "2-digit", hour12: false,
  }).format(new Date());
}

/** Today's date "YYYY-MM-DD" in [tz]. */
export function todayIn(tz: string): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit" })
    .format(new Date());
}
