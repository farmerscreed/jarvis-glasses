// POST /functions/v1/meeting   (Phase 2 — docs/PHASE2_DESIGN.md §5)
//   { op: "chunk", client_id, name: "003.m4a", seq, bytes, sha256, duration_s, title, started_at, kind? }
//        → the phone has uploaded recordings/<uid>/<client_id>/<name>; we download it, check size + sha256,
//          and record it. 409 { error: "mismatch" } means "upload it again".          → { ok, recording_id }
//   { op: "finish", client_id, chunks_total, duration_s, ended_at, bookmarks, title }
//        → the meeting has stopped and every chunk is up.                     → { status, uploaded }
//   { op: "delete", client_id }  → audio + rows removed from the cloud.       → { ok }
// M3 adds transcription after "chunk"; M4 adds the summary after "finish".
import { corsHeaders, json } from "../_shared/http.ts";
import { serviceClient, userClient } from "../_shared/supabaseClient.ts";
import { checkRateLimit, HOURLY } from "../_shared/rateLimit.ts";

const BUCKET = "recordings";
const CLIENT_ID = /^rec-[A-Za-z0-9-]{4,60}$/;
const CHUNK_NAME = /^\d{3}\.m4a$/;

async function sha256Hex(buf: ArrayBuffer): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", buf);
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

const iso = (ms: unknown) => {
  const n = Number(ms);
  return Number.isFinite(n) && n > 0 ? new Date(n).toISOString() : null;
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  try {
    const supabase = userClient(req);
    const { data: { user } } = await supabase.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);
    if (!await checkRateLimit(supabase, "meeting", 600, HOURLY)) return json({ error: "Too many requests." }, 429);
    const body = await req.json();
    const op = String(body.op ?? "");
    const clientId = String(body.client_id ?? "");
    if (!CLIENT_ID.test(clientId)) return json({ error: "bad client_id" }, 400);
    const db = serviceClient();

    const findRec = async () =>
      (await db.from("recordings").select("id,status,chunks_total").eq("user_id", user.id).eq("client_id", clientId).maybeSingle()).data;

    switch (op) {
      case "chunk": {
        const name = String(body.name ?? "");
        if (!CHUNK_NAME.test(name)) return json({ error: "bad chunk name" }, 400);
        const seq = parseInt(name, 10);
        const path = `${user.id}/${clientId}/${name}`;

        // 1. The recording row (created by the first chunk; title/start refreshed each time).
        let rec = await findRec();
        if (!rec) {
          const startedAt = iso(body.started_at) ?? new Date().toISOString();
          const { data, error } = await db.from("recordings").upsert({
            user_id: user.id, client_id: clientId, title: String(body.title ?? "").slice(0, 200),
            kind: String(body.kind ?? "meeting"), source: "phone", started_at: startedAt, status: "uploading",
          }, { onConflict: "user_id,client_id" }).select("id,status,chunks_total").single();
          if (error) return json({ error: error.message }, 500);
          rec = data;
        }

        // 2. Check what actually landed in storage against what the phone says it sent.
        const { data: blob, error: dlErr } = await db.storage.from(BUCKET).download(path);
        if (dlErr || !blob) return json({ error: "mismatch", detail: "object not found" }, 409);
        const buf = await blob.arrayBuffer();
        const sha = await sha256Hex(buf);
        if (buf.byteLength !== Number(body.bytes) || sha !== String(body.sha256 ?? "").toLowerCase()) {
          console.warn(`meeting chunk mismatch ${path}: ${buf.byteLength}/${body.bytes} ${sha.slice(0, 8)}/${String(body.sha256).slice(0, 8)}`);
          return json({ error: "mismatch" }, 409);
        }

        // 3. Record it (idempotent: a re-sent chunk just overwrites its row).
        const { error: cErr } = await db.from("recording_chunks").upsert({
          recording_id: rec!.id, seq, user_id: user.id, path, bytes: buf.byteLength, sha256: sha,
          duration_s: Math.max(0, Math.round(Number(body.duration_s) || 0)),
        }, { onConflict: "recording_id,seq" });
        if (cErr) return json({ error: cErr.message }, 500);

        const { data: sizes } = await db.from("recording_chunks").select("bytes").eq("recording_id", rec!.id);
        const total = (sizes ?? []).reduce((a, r) => a + (r.bytes ?? 0), 0);
        const done = rec!.chunks_total != null && (sizes?.length ?? 0) >= rec!.chunks_total;
        await db.from("recordings").update({
          audio_bytes: total, updated_at: new Date().toISOString(),
          ...(rec!.status === "recording" || rec!.status === "uploading" ? { status: done ? "uploaded" : "uploading" } : {}),
        }).eq("id", rec!.id);
        return json({ ok: true, recording_id: rec!.id });
      }

      case "finish": {
        const rec = await findRec();
        if (!rec) return json({ error: "unknown recording — upload a chunk first" }, 404);
        const total = Math.max(0, parseInt(String(body.chunks_total ?? 0), 10) || 0);
        const { count } = await db.from("recording_chunks").select("seq", { count: "exact", head: true }).eq("recording_id", rec.id);
        const uploaded = count ?? 0;
        const status = uploaded >= total ? "uploaded" : "uploading";
        const bookmarks = Array.isArray(body.bookmarks)
          ? body.bookmarks.slice(0, 500).map((b: { t_s?: unknown; label?: unknown }) => ({ t_s: Number(b.t_s) || 0, label: String(b.label ?? "").slice(0, 200) }))
          : [];
        const { error } = await db.from("recordings").update({
          chunks_total: total, duration_s: Math.max(0, Math.round(Number(body.duration_s) || 0)),
          ended_at: iso(body.ended_at) ?? new Date().toISOString(), bookmarks,
          ...(body.title != null ? { title: String(body.title).slice(0, 200) } : {}),
          ...(rec.status === "recording" || rec.status === "uploading" || rec.status === "uploaded" ? { status } : {}),
          updated_at: new Date().toISOString(),
        }).eq("id", rec.id);
        if (error) return json({ error: error.message }, 500);
        return json({ status, uploaded });
      }

      case "delete": {
        const prefix = `${user.id}/${clientId}`;
        const { data: objs } = await db.storage.from(BUCKET).list(prefix, { limit: 1000 });
        if (objs?.length) await db.storage.from(BUCKET).remove(objs.map((o) => `${prefix}/${o.name}`));
        await db.from("recordings").delete().eq("user_id", user.id).eq("client_id", clientId);
        return json({ ok: true });
      }

      default:
        return json({ error: "op must be chunk | finish | delete" }, 400);
    }
  } catch (e) {
    console.error("meeting error:", e);
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
