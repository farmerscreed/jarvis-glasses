// Phase 2 · M6b — minimal Google Drive v3 helpers for copying starred meetings to the owner's Drive.
// Scope: drive.file (JARVIS only sees files it created). Uses the stored Google connection (_shared/google.ts).
import { accessToken, googleApi } from "./google.ts";

const FOLDER = "application/vnd.google-apps.folder";

/** Find (or create) a folder named [name] under [parent] ("root" = My Drive). Returns its id. */
export async function ensureFolder(userId: string, name: string, parent = "root"): Promise<string> {
  const q = encodeURIComponent(`name = '${name.replace(/'/g, "\\'")}' and mimeType = '${FOLDER}' and '${parent}' in parents and trashed = false`);
  const found = await googleApi(userId, `https://www.googleapis.com/drive/v3/files?q=${q}&fields=files(id)&pageSize=1`);
  if (found.files?.length) return found.files[0].id;
  const made = await googleApi(userId, "https://www.googleapis.com/drive/v3/files?fields=id", {
    method: "POST",
    body: JSON.stringify({ name, mimeType: FOLDER, parents: [parent] }),
  });
  return made.id;
}

/** Upload [bytes] as [name] into folder [parent] (multipart upload). Returns the new file id. */
export async function uploadFile(userId: string, parent: string, name: string, bytes: Uint8Array, mime: string): Promise<string> {
  const token = await accessToken(userId);
  const boundary = "jarvis" + crypto.randomUUID().replace(/-/g, "");
  const head = new TextEncoder().encode(
    `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify({ name, parents: [parent] })}\r\n` +
      `--${boundary}\r\nContent-Type: ${mime}\r\n\r\n`,
  );
  const tail = new TextEncoder().encode(`\r\n--${boundary}--`);
  const body = new Uint8Array(head.length + bytes.length + tail.length);
  body.set(head, 0); body.set(bytes, head.length); body.set(tail, head.length + bytes.length);
  const res = await fetch("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": `multipart/related; boundary=${boundary}` },
    body,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`Drive upload ${res.status}: ${text.slice(0, 200)}`);
  return JSON.parse(text).id;
}
