// AES-GCM encryption for secrets we store on the user's behalf (the Google refresh token).
// Key: GOOGLE_TOKEN_KEY secret = base64 of 32 random bytes (e.g. `openssl rand -base64 32`).
// Stored form: base64(12-byte IV || ciphertext+tag).

let keyPromise: Promise<CryptoKey> | null = null;

function key(): Promise<CryptoKey> {
  if (!keyPromise) {
    const b64 = Deno.env.get("GOOGLE_TOKEN_KEY");
    if (!b64) throw new Error("GOOGLE_TOKEN_KEY is not set");
    const raw = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    if (raw.length !== 32) throw new Error("GOOGLE_TOKEN_KEY must be 32 bytes (base64)");
    keyPromise = crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
  }
  return keyPromise;
}

const toB64 = (b: Uint8Array) => btoa(String.fromCharCode(...b));
const fromB64 = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

export async function encryptSecret(plain: string): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(
    await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await key(), new TextEncoder().encode(plain)),
  );
  const out = new Uint8Array(iv.length + ct.length);
  out.set(iv);
  out.set(ct, iv.length);
  return toB64(out);
}

export async function decryptSecret(stored: string): Promise<string> {
  const all = fromB64(stored);
  const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: all.slice(0, 12) }, await key(), all.slice(12));
  return new TextDecoder().decode(pt);
}
