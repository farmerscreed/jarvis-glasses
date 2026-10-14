// POST /functions/v1/meeting   (Phase 2 — docs/PHASE2_DESIGN.md §5)
//   { op: "chunk", client_id, name: "003.m4a", seq, bytes, sha256, duration_s, title, started_at, kind? }
//        → the phone has uploaded recordings/<uid>/<client_id>/<name>; we download it, check size + sha256,
//          and record it. 409 { error: "mismatch" } means "upload it again".          → { ok, recording_id }
//   { op: "finish", client_id, chunks_total, duration_s, ended_at, bookmarks, title }
//        → the meeting has stopped and every chunk is up.                     → { status, uploaded }
//   { op: "delete", client_id }  → audio + rows removed from the cloud.       → { ok }
// M3: every verified chunk is transcribed into recording_segments; when all are done the recording becomes
// "transcribed". M4: then the summary (Claude), the meeting memory (searchable), and a push "summary ready".
//
// Long work never runs inside the caller's request: each chunk and each summary is its own invocation of this
// function ("work-chunk" / "work-summary", authorised by MEETING_INTERNAL_SECRET), so each gets the full
// time budget (~150 s). A pg_cron job posts { op: "sweep" } every 5 minutes and re-kicks anything stuck,
// failed or never started, so the pipeline heals itself without the phone. Deployed with --no-verify-jwt:
// user ops verify the JWT here (auth.getUser), internal ops check the secret.
import { SupabaseClient } from "npm:@supabase/supabase-js@2";
import { corsHeaders, json } from "../_shared/http.ts";
import { serviceClient, userClient } from "../_shared/supabaseClient.ts";
import { checkRateLimit, HOURLY } from "../_shared/rateLimit.ts";
import { transcribeChunk } from "../_shared/meetingStt.ts";
import { Line, memoryText, pushBody, summarise } from "../_shared/meetingSummary.ts";
import { embed } from "../_shared/embeddings.ts";
import { pushConfigured, sendPush } from "../_shared/fcm.ts";

declare const EdgeRuntime: { waitUntil(p: Promise<unknown>): void };

const GEMINI_API_KEY = Deno.env.get("GEMINI_API_KEY") ?? "";
const STT_MODELS = [...new Set([Deno.env.get("GEMINI_MEETING_MODEL") ?? "gemini-2.5-flash", "gemini-2.5-flash-lite"])];
const VOCAB = Deno.env.get("STT_VOCAB") ?? "";
const MAX_ATTEMPTS = 3;
const STALE_MS = 5 * 60_000; // a "transcribing"/"summarising" job older than this is assumed dead and retried
const INTERNAL_SECRET = Deno.env.get("MEETING_INTERNAL_SECRET") ?? "";
const SELF_URL = `${Deno.env.get("SUPABASE_URL")}/functions/v1/meeting`;

/** Start [body.op] in a fresh invocation of this function (its own time budget). Fire-and-forget. */
function kick(body: Record<string, unknown>) {
  if (!INTERNAL_SECRET) { console.error("MEETING_INTERNAL_SECRET not set: can't start background work"); return; }
  EdgeRuntime.waitUntil(
    fetch(SELF_URL, {
      method: "POST",
      headers: { "content-type": "application/json", "x-meeting-secret": INTERNAL_SECRET },
      body: JSON.stringify(body),
    }).then((r) => r.body?.cancel()).catch((e) => console.error(`kick ${body.op}: ${e}`)),
  );
}

/** Constant-time compare for the internal secret. */
function secretOk(got: string | null): boolean {
  if (!INTERNAL_SECRET || !got || got.length !== INTERNAL_SECRET.length) return false;
  let d = 0;
  for (let i = 0; i < got.length; i++) d |= got.charCodeAt(i) ^ INTERNAL_SECRET.charCodeAt(i);
  return d === 0;
}

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
async function transcribeOne(db: SupabaseClient, recId: string, seq: number) {
  const staleBefore = new Date(Date.now() - STALE_MS).toISOString();
  const { data: claimed } = await db.from("recording_chunks")
    .update({ status: "transcribing", started_at: new Date().toISOString() })
    .eq("recording_id", recId).eq("seq", seq).lt("attempts", MAX_ATTEMPTS)
    .or(`status.in.(uploaded,failed),and(status.eq.transcribing,started_at.lt.${staleBefore})`)
    .select("path,attempts,user_id");
  if (!claimed?.length) return;
  const chunk = claimed[0];
  await db.from("recording_chunks").update({ attempts: (chunk.attempts ?? 0) + 1 }).eq("recording_id", recId).eq("seq", seq);
  try {
    if (!GEMINI_API_KEY) throw new Error("GEMINI_API_KEY not set");
    const { data: blob, error: dlErr } = await db.storage.from(BUCKET).download(chunk.path);
    if (dlErr || !blob) throw new Error(`download: ${dlErr?.message ?? "missing"}`);
    const bytes = new Uint8Array(await blob.arrayBuffer());
    // Speaker labels already used earlier in the meeting help keep them consistent.
    const { data: prev } = await db.from("recording_segments").select("speaker").eq("recording_id", recId).lt("seq", seq).limit(2000);
    const known = [...new Set((prev ?? []).map((r) => r.speaker as string))].filter((s) => s && s !== "Media").slice(0, 12);
    const r = await transcribeChunk(bytes, { apiKey: GEMINI_API_KEY, models: STT_MODELS, vocab: VOCAB, knownSpeakers: known });
    await db.from("recording_segments").delete().eq("recording_id", recId).eq("seq", seq);
    if (r.segments.length) {
      const { error } = await db.from("recording_segments").insert(r.segments.map((x, idx) => ({
        recording_id: recId, seq, idx, user_id: chunk.user_id, t_start_s: x.start, t_end_s: x.end, speaker: x.speaker, text: x.text,
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

/** Once the meeting has stopped and every chunk is done (or gave up), the transcript is complete → summary. */
async function maybeTranscribed(db: SupabaseClient, recId: string) {
  const { data: rec } = await db.from("recordings").select("status,chunks_total,updated_at").eq("id", recId).maybeSingle();
  if (!rec || rec.chunks_total == null) return;
  // A summary that failed earlier, or got stuck, is retried whenever this runs again (e.g. a repeated "finish").
  const stuck = rec.status === "summarising" && Date.parse(rec.updated_at) < Date.now() - STALE_MS;
  if (rec.status === "transcribed" || stuck) return kick({ op: "work-summary", recording_id: recId });
  if (!["uploaded", "transcribing"].includes(rec.status)) return;
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
  kick({ op: "work-summary", recording_id: recId });
}

const AUDIO_KEEP_DAYS = 90;

/** M4: summary → meeting memory → push. Claimed (transcribed → summarising) so it runs once. */
async function summariseRecording(db: SupabaseClient, recId: string) {
  const staleBefore = new Date(Date.now() - STALE_MS).toISOString();
  const { data: claimed } = await db.from("recordings")
    .update({ status: "summarising", updated_at: new Date().toISOString() })
    .eq("id", recId)
    .or(`status.eq.transcribed,and(status.eq.summarising,updated_at.lt.${staleBefore})`)
    .select("id,user_id,client_id,title,started_at,duration_s,bookmarks,memory_id,starred,error");
  const rec = claimed?.[0];
  if (!rec) return;
  try {
    // The whole meeting as one timeline: each chunk's lines shifted by the length of the chunks before it.
    const { data: chunks } = await db.from("recording_chunks").select("seq,duration_s").eq("recording_id", recId).order("seq");
    const offset = new Map<number, number>();
    let acc = 0;
    for (const c of chunks ?? []) { offset.set(c.seq, acc); acc += c.duration_s ?? 0; }
    const { data: segs } = await db.from("recording_segments").select("seq,idx,t_start_s,speaker,text")
      .eq("recording_id", recId).order("seq").order("idx").limit(20000);
    const lines: Line[] = (segs ?? []).map((x) => ({ t: (offset.get(x.seq) ?? 0) + Number(x.t_start_s), speaker: x.speaker, text: x.text }));

    const date = new Date(rec.started_at).toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short", year: "numeric", timeZone: "Africa/Lagos" });
    const t0 = Date.now();
    const s = await summarise(lines, { title: rec.title, date, durationS: rec.duration_s, bookmarks: rec.bookmarks ?? [] });
    const title = (rec.title?.trim() || s.title || "Meeting").slice(0, 200);
    s.title = title;
    const speakers = Object.fromEntries(s.speakers.filter((x) => x.label && x.name).map((x) => [x.label, x.name]));

    // The searchable memory (recall/chat find meetings through it). Updated in place on a re-run.
    let memoryId: string | null = rec.memory_id ?? null;
    if (s.is_meeting) {
      const text = memoryText(s, date);
      const row = { text, embedding: await embed(text), tags: ["meeting"], metadata: { recording_id: recId, client_id: rec.client_id } };
      if (memoryId) {
        await db.from("memories").update(row).eq("id", memoryId).eq("user_id", rec.user_id);
      } else {
        const { data: m, error } = await db.from("memories")
          .insert({ ...row, user_id: rec.user_id, type: "meeting", created_at: rec.started_at, client_id: `meeting-${rec.client_id}` })
          .select("id").single();
        if (error) throw new Error(`memory: ${error.message}`);
        memoryId = m.id;
      }
    }

    await db.from("recordings").update({
      status: "ready", summary: s, title, speakers, memory_id: memoryId,
      audio_expires_at: rec.starred ? null : new Date(Date.now() + AUDIO_KEEP_DAYS * 86_400_000).toISOString(),
      error: rec.error?.includes("part(s)") ? rec.error : null, updated_at: new Date().toISOString(),
    }).eq("id", recId);
    console.log(`meeting summary ${recId}: ${Date.now() - t0} ms, meeting=${s.is_meeting}, ${s.action_items.length} actions`);

    if (pushConfigured()) {
      await sendPush(rec.user_id, title, pushBody(s), { kind: "meeting", id: rec.client_id })
        .catch((e) => console.warn(`meeting push: ${e instanceof Error ? e.message : e}`));
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.error(`meeting summary ${recId} failed: ${msg}`);
    // Back to "transcribed" so the next finish/retry tries again; the transcript itself is safe.
    await db.from("recordings").update({ status: "transcribed", error: `summary: ${msg.slice(0, 400)}`, updated_at: new Date().toISOString() })
      .eq("id", recId);
  }
}

/** Start a transcription for every chunk of [recId] that still needs one; then check whether it's complete. */
async function processPending(db: SupabaseClient, recId: string) {
  const { data: chunks } = await db.from("recording_chunks").select("seq,status,attempts").eq("recording_id", recId).order("seq");
  for (const c of chunks ?? []) {
    if (c.status !== "done" && c.attempts < MAX_ATTEMPTS) kick({ op: "work-chunk", recording_id: recId, seq: c.seq });
  }
  await maybeTranscribed(db, recId);
}

/** Every 5 minutes (pg_cron): restart anything stuck, failed or never started, for every user. */
async function sweep(db: SupabaseClient): Promise<Record<string, number>> {
  const stale = new Date(Date.now() - STALE_MS).toISOString();
  // A job that died on its last allowed attempt can't be retried: mark it failed so the meeting can finish.
  await db.from("recording_chunks").update({ status: "failed", error: "timed out" })
    .eq("status", "transcribing").lt("started_at", stale).gte("attempts", MAX_ATTEMPTS);
  const { data: chunks } = await db.from("recording_chunks").select("recording_id,seq")
    .lt("attempts", MAX_ATTEMPTS)
    .or(`status.in.(uploaded,failed),and(status.eq.transcribing,started_at.lt.${stale})`)
    .limit(40);
  for (const c of chunks ?? []) kick({ op: "work-chunk", recording_id: c.recording_id, seq: c.seq });
  // Recordings whose last step never ran (all chunks settled, or a summary that failed / got stuck).
  const { data: recs } = await db.from("recordings").select("id")
    .not("chunks_total", "is", null)
    .or(`status.in.(uploaded,transcribing,transcribed),and(status.eq.summarising,updated_at.lt.${stale})`)
    .lt("updated_at", new Date(Date.now() - 60_000).toISOString())
    .limit(40);
  for (const r of recs ?? []) await maybeTranscribed(db, r.id);
  return { chunks: chunks?.length ?? 0, recordings: recs?.length ?? 0 };
}

const iso = (ms: unknown) => {
  const n = Number(ms);
  return Number.isFinite(n) && n > 0 ? new Date(n).toISOString() : null;
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  try {
    // Internal work (self-kicks and the pg_cron sweep): answer at once, do the work in this invocation.
    if (req.headers.get("x-meeting-secret") != null) {
      if (!secretOk(req.headers.get("x-meeting-secret"))) return json({ error: "unauthorized" }, 401);
      const b = await req.json();
      const db = serviceClient();
      switch (b.op) {
        case "work-chunk":
          EdgeRuntime.waitUntil(transcribeOne(db, String(b.recording_id), Number(b.seq)));
          return json({ accepted: true }, 202);
        case "work-summary":
          EdgeRuntime.waitUntil(summariseRecording(db, String(b.recording_id)));
          return json({ accepted: true }, 202);
        case "sweep":
          return json(await sweep(db));
        default:
          return json({ error: "unknown internal op" }, 400);
      }
    }

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
        kick({ op: "work-chunk", recording_id: rec!.id, seq });
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
          status = rec.status === "ready" ? "ready" : "transcribing";
          await processPending(db, rec.id); // also retries a failed summary
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
