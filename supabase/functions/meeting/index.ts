// POST /functions/v1/meeting   (Phase 2 — docs/PHASE2_DESIGN.md §5)
//   { op: "chunk", client_id, name: "003.m4a", seq, bytes, sha256, duration_s, title, started_at, kind? }
//        → the phone has uploaded recordings/<uid>/<client_id>/<name>; we download it, check size + sha256,
//          and record it. 409 { error: "mismatch" } means "upload it again".          → { ok, recording_id }
//   { op: "finish", client_id, chunks_total, duration_s, ended_at, bookmarks, title }
//        → the meeting has stopped and every chunk is up.                     → { status, uploaded }
//   { op: "delete", client_id }  → audio + rows removed from the cloud.       → { ok }
// M3: every verified chunk is transcribed in the background (EdgeRuntime.waitUntil) into recording_segments;
// "finish" re-kicks anything pending/failed. When all chunks are done the recording becomes "transcribed".
// M4 adds the summary after that.
import { SupabaseClient } from "npm:@supabase/supabase-js@2";
import { corsHeaders, json } from "../_shared/http.ts";
import { serviceClient, userClient } from "../_shared/supabaseClient.ts";
import { checkRateLimit, HOURLY } from "../_shared/rateLimit.ts";
import { transcribeChunk } from "../_shared/meetingStt.ts";

declare const EdgeRuntime: { waitUntil(p: Promise<unknown>): void };

const GEMINI_API_KEY = Deno.env.get("GEMINI_API_KEY") ?? "";
const STT_MODELS = [...new Set([Deno.env.get("GEMINI_MEETING_MODEL") ?? "gemini-2.5-flash", "gemini-2.5-flash-lite"])];
const VOCAB = Deno.env.get("STT_VOCAB") ?? "";
const MAX_ATTEMPTS = 3;
const STALE_MS = 10 * 60_000; // a "transcribing" chunk older than this is assumed dead and retried

const BUCKET = "recordings";
const CLIENT_ID = /^rec-[A-Za-z0-9-]{4,60}$/;
const CHUNK_NAME = /^\d{3}\.m4a$/;

async function sha256Hex(buf: ArrayBuffer): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", buf);
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Claim one chunk (so two triggers never transcribe it twice), transcribe it, store its lines.
 * [audio] is passed when we already have the bytes (just verified); otherwise it's downloaded.
 */
async function transcribeOne(db: SupabaseClient, recId: string, seq: number, userId: string, audio?: Uint8Array) {
  const staleBefore = new Date(Date.now() - STALE_MS).toISOString();
  const { data: claimed } = await db.from("recording_chunks")
    .update({ status: "transcribing", started_at: new Date().toISOString() })
    .eq("recording_id", recId).eq("seq", seq).lt("attempts", MAX_ATTEMPTS)
    .or(`status.in.(uploaded,failed),and(status.eq.transcribing,started_at.lt.${staleBefore})`)
    .select("path,attempts");
  if (!claimed?.length) return;
  const chunk = claimed[0];
  await db.from("recording_chunks").update({ attempts: (chunk.attempts ?? 0) + 1 }).eq("recording_id", recId).eq("seq", seq);
  try {
    if (!GEMINI_API_KEY) throw new Error("GEMINI_API_KEY not set");
    let bytes = audio;
    if (!bytes) {
      const { data: blob, error } = await db.storage.from(BUCKET).download(chunk.path);
      if (error || !blob) throw new Error(`download: ${error?.message ?? "missing"}`);
      bytes = new Uint8Array(await blob.arrayBuffer());
    }
    // Speaker labels already used earlier in the meeting help keep them consistent.
    const { data: prev } = await db.from("recording_segments").select("speaker").eq("recording_id", recId).lt("seq", seq).limit(2000);
    const known = [...new Set((prev ?? []).map((r) => r.speaker as string))].filter((s) => s && s !== "Media").slice(0, 12);
    const r = await transcribeChunk(bytes, { apiKey: GEMINI_API_KEY, models: STT_MODELS, vocab: VOCAB, knownSpeakers: known });
    await db.from("recording_segments").delete().eq("recording_id", recId).eq("seq", seq);
    if (r.segments.length) {
      const { error } = await db.from("recording_segments").insert(r.segments.map((x, idx) => ({
        recording_id: recId, seq, idx, user_id: userId, t_start_s: x.start, t_end_s: x.end, speaker: x.speaker, text: x.text,
      })));
      if (error) throw new Error(`segments: ${error.message}`);
    }
    await db.from("recording_chunks").update({ status: "done", error: null, model: r.model, transcribed_at: new Date().toISOString() })
      .eq("recording_id", recId).eq("seq", seq);
    console.log(`meeting stt ${recId}/${seq}: ${r.segments.length} lines, ${r.model}, ${r.ms} ms, ${r.tokens ?? "?"} tokens`);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.error(`meeting stt ${recId}/${seq} failed: ${msg}`);
    await db.from("recording_chunks").update({ status: "failed", error: msg.slice(0, 500) }).eq("recording_id", recId).eq("seq", seq);
  }
  await maybeTranscribed(db, recId);
}

/** Once the meeting has stopped and every chunk is done (or gave up), the transcript is complete. */
async function maybeTranscribed(db: SupabaseClient, recId: string) {
  const { data: rec } = await db.from("recordings").select("status,chunks_total").eq("id", recId).maybeSingle();
  if (!rec || rec.chunks_total == null || !["uploaded", "transcribing"].includes(rec.status)) return;
  const { data: chunks } = await db.from("recording_chunks").select("status,attempts").eq("recording_id", recId);
  const settled = (chunks ?? []).filter((c) => c.status === "done" || (c.status === "failed" && c.attempts >= MAX_ATTEMPTS));
  if ((chunks?.length ?? 0) < rec.chunks_total || settled.length < rec.chunks_total) {
    if (rec.status !== "transcribing") await db.from("recordings").update({ status: "transcribing" }).eq("id", recId);
    return;
  }
  const failed = settled.filter((c) => c.status === "failed").length;
  await db.from("recordings").update({
    status: "transcribed", error: failed ? `${failed} part(s) couldn't be transcribed` : null, updated_at: new Date().toISOString(),
  }).eq("id", recId).in("status", ["uploaded", "transcribing"]);
  // M4: summary, memory and push start here.
}

/** Transcribe every chunk that still needs it, a few at a time. */
async function processPending(db: SupabaseClient, recId: string, userId: string) {
  const { data: chunks } = await db.from("recording_chunks").select("seq,status,attempts").eq("recording_id", recId).order("seq");
  const todo = (chunks ?? []).filter((c) => c.status !== "done" && c.attempts < MAX_ATTEMPTS).map((c) => c.seq as number);
  for (let i = 0; i < todo.length; i += 3) {
    await Promise.all(todo.slice(i, i + 3).map((seq) => transcribeOne(db, recId, seq, userId)));
  }
  await maybeTranscribed(db, recId);
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
        EdgeRuntime.waitUntil(transcribeOne(db, rec!.id, seq, user.id, new Uint8Array(buf)));
        return json({ ok: true, recording_id: rec!.id });
      }

      case "finish": {
        const rec = await findRec();
        if (!rec) return json({ error: "unknown recording — upload a chunk first" }, 404);
        const total = Math.max(0, parseInt(String(body.chunks_total ?? 0), 10) || 0);
        const { count } = await db.from("recording_chunks").select("seq", { count: "exact", head: true }).eq("recording_id", rec.id);
        const uploaded = count ?? 0;
        let status = uploaded >= total ? "uploaded" : "uploading";
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
        if (status === "uploaded") {
          status = "transcribing";
          EdgeRuntime.waitUntil(processPending(db, rec.id, user.id));
        }
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
