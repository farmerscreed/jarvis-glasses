// Push notifications via Firebase Cloud Messaging (HTTP v1), 2026-10-11.
// Secret FCM_SERVICE_ACCOUNT = the Firebase service-account JSON (Project settings → Service accounts →
// Generate new private key), pasted whole. Any function can call sendPush(userId, …) to reach every
// phone that user registered ("Your meeting summary is ready").
import { serviceClient } from "./supabaseClient.ts";

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri?: string;
}

let sa: ServiceAccount | null | undefined;
function account(): ServiceAccount | null {
  if (sa !== undefined) return sa;
  const raw = Deno.env.get("FCM_SERVICE_ACCOUNT");
  let parsed: ServiceAccount | null = null;
  try { parsed = raw ? JSON.parse(raw) : null; } catch (_) { parsed = null; }
  sa = parsed;
  return parsed;
}

export function pushConfigured(): boolean {
  return account() !== null;
}

const b64url = (b: Uint8Array) => btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const enc = new TextEncoder();

let cached: { token: string; until: number } | null = null;

/** OAuth access token for FCM, from a self-signed service-account JWT (RS256). */
export async function fcmAccessToken(): Promise<string> {
  if (cached && cached.until > Date.now()) return cached.token;
  const a = account();
  if (!a) throw new Error("FCM_SERVICE_ACCOUNT is not set");
  const pem = a.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const key = await crypto.subtle.importKey(
    "pkcs8",
    Uint8Array.from(atob(pem), (c) => c.charCodeAt(0)),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const now = Math.floor(Date.now() / 1000);
  const tokenUri = a.token_uri ?? "https://oauth2.googleapis.com/token";
  const header = b64url(enc.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = b64url(enc.encode(JSON.stringify({
    iss: a.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: tokenUri,
    iat: now,
    exp: now + 3600,
  })));
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(`${header}.${claims}`)));
  const res = await fetch(tokenUri, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${header}.${claims}.${b64url(sig)}`,
    }),
  });
  const body = await res.json();
  if (!res.ok) throw new Error(`FCM auth failed: ${body.error_description ?? body.error ?? res.status}`);
  cached = { token: body.access_token, until: Date.now() + (body.expires_in ?? 3600) * 1000 - 60_000 };
  return cached.token;
}

/**
 * Notify every registered phone of [userId]. [data] reaches the app too (e.g. {kind:"meeting", id}).
 * Dead tokens are removed. Returns how many phones accepted it.
 */
export async function sendPush(
  userId: string,
  title: string,
  body: string,
  data: Record<string, string> = {},
): Promise<number> {
  const a = account();
  if (!a) throw new Error("Push isn't set up on the server yet.");
  const db = serviceClient();
  const { data: rows } = await db.from("push_tokens").select("token").eq("user_id", userId);
  if (!rows?.length) return 0;
  const access = await fcmAccessToken();
  let sent = 0;
  for (const { token } of rows) {
    const res = await fetch(`https://fcm.googleapis.com/v1/projects/${a.project_id}/messages:send`, {
      method: "POST",
      headers: { authorization: `Bearer ${access}`, "content-type": "application/json" },
      body: JSON.stringify({
        message: {
          token,
          notification: { title, body },
          data: { ...data, title, body },
          android: { priority: "high", notification: { channel_id: "jarvis_updates" } },
        },
      }),
    });
    if (res.ok) { sent++; continue; }
    const err = await res.text();
    // The app was uninstalled / the token rotated — forget it.
    if (res.status === 404 || /UNREGISTERED|INVALID_ARGUMENT.*registration/i.test(err)) {
      await db.from("push_tokens").delete().eq("token", token);
    } else {
      console.error(`FCM send ${res.status}: ${err.slice(0, 300)}`);
    }
  }
  return sent;
}
