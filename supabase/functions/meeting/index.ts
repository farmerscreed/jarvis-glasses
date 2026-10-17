// POST /functions/v1/meeting   (Phase 2 — docs/PHASE2_DESIGN.md §5–7)
//
// Phone → cloud (user JWT):
//   { op: "chunk", client_id, name: "003.m4a", bytes, sha256, duration_s, title, started_at, kind? }
//        → the phone uploaded recordings/<uid>/<client_id>/<name>; we check size + sha256 and record it.
//          409 { error: "mismatch" } means "upload it again".                          → { ok, recording_id }
//   { op: "finish", client_id, chunks_total, duration_s, ended_at, bookmarks, title }   → { status, uploaded }
//   { op: "delete", client_id }        → audio, transcript, summary and the meeting memory removed.  → { ok }
// Library (M5):
//   { op: "list" }                     → { recordings: [...] } every recording (newest first) + storage totals
//   { op: "get", client_id }           → { recording, lines: [{t,end,speaker,text,seq}], chunks: [{seq,duration_s}] }
//   { op: "audio-urls", client_id }    → { urls: [{seq, url, duration_s}] } (1 h signed links; audio not on phone)
//   { op: "star", client_id, starred } · { op: "title", client_id, title } · { op: "speaker", client_id, label, name }
//   { op: "tick", client_id, index, done } · { op: "delete-audio", client_id } (keeps all the text)
//   { op: "ask", client_id, question } → { answer, moments: [{t, quote}] }  (from this meeting's transcript)
//   { op: "settings", audio_keep_days? } → { audio_keep_days }  (30 | 90 | 365 | 0 = forever)
//   { op: "costs" }                    → this month's AI calls per job ($ estimates) + the model routes
//
// Background (header x-meeting-secret = MEETING_INTERNAL_SECRET; deployed with verify_jwt = false):
//   work-chunk / work-summary (one job per invocation → full time budget each), sweep (pg_cron, every 5 min:
//   restarts anything stuck/failed/unstarted), retention (pg_cron, daily: deletes expired unstarred audio),
//   work-drive (M6b: a starred meeting → Google Drive/JARVIS/Recordings, once Google is connected).
// M3: each verified chunk → Gemini transcript lines (recording_segments). M4: all chunks done → summary (model
// switchboard, job meeting_summary) → searchable `meeting` memory → push "‹title› — Summary ready".
import { SupabaseClient } from "npm:@supabase/supabase-js@2";
import { corsHeaders, json } from "../_shared/http.ts";
import { serviceClient, userClient } from "../_shared/supabaseClient.ts";
import { checkRateLimit, HOURLY } from "../_shared/rateLimit.ts";
import { transcribeChunk } from "../_shared/meetingStt.ts";
import { analyseVideoChunk, VideoResult } from "../_shared/meetingVideo.ts";
import { askMeeting, Line, memoryText, pushBody, Summary, summarise, summariseVideo, videoPushBody } from "../_shared/meetingSummary.ts";
import { estimateUsd, logCall, routesFor } from "../_shared/llm.ts";
import { embed } from "../_shared/embeddings.ts";
import { pushConfigured, sendPush } from "../_shared/fcm.ts";
import { connectionStatus } from "../_shared/google.ts";
import { ensureFolder, uploadFile } from "../_shared/drive.ts";

declare const EdgeRuntime: { waitUntil(p: Promise<unknown>): void };

const GEMINI_API_KEY = Deno.env.get("GEMINI_API_KEY") ?? "";
const STT_FALLBACK = ["gemini-2.5-flash", "gemini-2.5-flash-lite"];
const VOCAB = Deno.env.get("STT_VOCAB") ?? "";
const MAX_ATTEMPTS = 3;
const STALE_MS = 5 * 60_000; // a "transcribing"/"summarising" job older than this is assumed dead and retried
const DEFAULT_KEEP_DAYS = 90;
const INTERNAL_SECRET = Deno.env.get("MEETING_INTERNAL_SECRET") ?? "";
const SELF_URL = `${Deno.env.get("SUPABASE_URL")}/functions/v1/meeting`;

const BUCKET = "recordings";
const CLIENT_ID = /^rec-[A-Za-z0-9-]{4,60}$/;
const CHUNK_NAME = /^\d{3}\.(m4a|mp4)$/; // meeting audio, or a video's analysis-copy piece (Phase 3)
const LIST_FIELDS =
  "id,client_id,kind,source,title,started_at,ended_at,duration_s,status,chunks_total,bookmarks,speakers,summary,action_done,audio_bytes,starred,audio_expires_at,audio_deleted_at,error,drive_status,drive_copied_at,frames";

const now = () => new Date().toISOString();
const iso = (ms: unknown) => {
  const n = Number(ms);
  return Number.isFinite(n) && n > 0 ? new Date(n).toISOString() : null;
};
const lagosDate = (ts: string) =>
  new Date(ts).toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short", year: "numeric", timeZone: "Africa/Lagos" });

// ── background plumbing ──────────────────────────────────────────────────────────────────────────

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

async function sha256Hex(buf: ArrayBuffer): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", buf);
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** A deterministic UUID (version-5 style, from SHA-256) for [name]. */
async function stableUuid(name: string): Promise<string> {
  const h = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(name))).slice(0, 16);
  h[6] = (h[6] & 0x0f) | 0x50; // version 5
  h[8] = (h[8] & 0x3f) | 0x80; // RFC 4122 variant
  const x = [...h].map((b) => b.toString(16).padStart(2, "0")).join("");
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`;
}

async function keepDays(db: SupabaseClient, userId: string): Promise<number> {
  const { data } = await db.from("profile").select("audio_keep_days").eq("user_id", userId).maybeSingle();
  return data?.audio_keep_days ?? DEFAULT_KEEP_DAYS;
}

/** When this recording's audio should go (null = keep: starred, or "forever"). Counted from the meeting's end. */
function expiryFor(endedAt: string | null, days: number, starred: boolean): string | null {
  if (starred || days === 0) return null;
  return new Date((endedAt ? Date.parse(endedAt) : Date.now()) + days * 86_400_000).toISOString();
}

// ── M3: transcription ────────────────────────────────────────────────────────────────────────────

/** Claim one chunk (so two triggers never transcribe it twice), transcribe it, store its lines. */
async function transcribeOne(db: SupabaseClient, recId: string, seq: number) {
  const staleBefore = new Date(Date.now() - STALE_MS).toISOString();
  const { data: claimed } = await db.from("recording_chunks")
    .update({ status: "transcribing", started_at: now() })
    .eq("recording_id", recId).eq("seq", seq).lt("attempts", MAX_ATTEMPTS)
    .or(`status.in.(uploaded,failed),and(status.eq.transcribing,started_at.lt.${staleBefore})`)
    .select("path,attempts,user_id");
  if (!claimed?.length) return;
  const chunk = claimed[0];
  await db.from("recording_chunks").update({ attempts: (chunk.attempts ?? 0) + 1 }).eq("recording_id", recId).eq("seq", seq);
  const t0 = Date.now();
  let job = "meeting_stt";
  try {
    if (!GEMINI_API_KEY) throw new Error("GEMINI_API_KEY not set");
    const { data: blob, error: dlErr } = await db.storage.from(BUCKET).download(chunk.path);
    if (dlErr || !blob) throw new Error(`download: ${dlErr?.message ?? "missing"}`);
    const bytes = new Uint8Array(await blob.arrayBuffer());
    // Speaker labels already used earlier in the meeting help keep them consistent.
    const { data: prev } = await db.from("recording_segments").select("speaker").eq("recording_id", recId).lt("seq", seq).limit(2000);
    const known = [...new Set((prev ?? []).map((r) => r.speaker as string))].filter((s) => s && s !== "Media").slice(0, 12);
    // Phase 3: a video piece is WATCHED (said + seen + key moments); meeting audio is transcribed.
    const { data: recKind } = await db.from("recordings").select("kind").eq("id", recId).maybeSingle();
    const isVideo = recKind?.kind === "video" || recKind?.kind === "walk";
    job = isVideo ? "video_stt" : "meeting_stt";
    const route = (await routesFor(job, db)).filter((r) => r.provider === "gemini");
    const models = route.length ? route.map((r) => r.model) : STT_FALLBACK;
    const r = isVideo
      ? await analyseVideoChunk(bytes, { apiKey: GEMINI_API_KEY, models, kind: recKind!.kind, vocab: VOCAB })
      : await transcribeChunk(bytes, { apiKey: GEMINI_API_KEY, models, vocab: VOCAB, knownSpeakers: known });
    if (isVideo) await db.from("recording_chunks").update({ moments: (r as VideoResult).moments }).eq("recording_id", recId).eq("seq", seq);
    await db.from("recording_segments").delete().eq("recording_id", recId).eq("seq", seq);
    if (r.segments.length) {
      const { error } = await db.from("recording_segments").insert(r.segments.map((x, idx) => ({
        recording_id: recId, seq, idx, user_id: chunk.user_id, t_start_s: x.start, t_end_s: x.end, speaker: x.speaker, text: x.text,
      })));
      if (error) throw new Error(`segments: ${error.message}`);
    }
    await db.from("recording_chunks").update({ status: "done", error: null, model: r.model, transcribed_at: now() })
      .eq("recording_id", recId).eq("seq", seq);
    const entry = route.find((x) => x.model === r.model);
    await logCall({
      userId: chunk.user_id, job, provider: "gemini", model: r.model, tokensIn: r.tokensIn, tokensOut: r.tokensOut,
      usd: estimateUsd(r.model, r.tokensIn ?? 0, r.tokensOut ?? 0, entry), ms: r.ms, ok: true,
    }, db);
    console.log(`${job} ${recId}/${seq}: ${r.segments.length} lines, ${r.model}, ${r.ms} ms`);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.error(`${job} ${recId}/${seq} failed: ${msg}`);
    await db.from("recording_chunks").update({ status: "failed", error: msg.slice(0, 500) }).eq("recording_id", recId).eq("seq", seq);
    await logCall({ userId: chunk.user_id, job, provider: "gemini", model: "?", ms: Date.now() - t0, ok: false, error: msg }, db);
  }
  await maybeTranscribed(db, recId);
}

/** Once the meeting has stopped and every chunk is done (or gave up), the transcript is complete → summary. */
async function maybeTranscribed(db: SupabaseClient, recId: string) {
  const { data: rec } = await db.from("recordings").select("status,chunks_total,updated_at").eq("id", recId).maybeSingle();
  if (!rec || rec.chunks_total == null) return;
  // A summary that failed earlier, or got stuck, is retried whenever this runs again (sweep, repeated finish).
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
    status: "transcribed", error: failed ? `${failed} part(s) couldn't be transcribed` : null, updated_at: now(),
  }).eq("id", recId).in("status", ["uploaded", "transcribing"]);
  kick({ op: "work-summary", recording_id: recId });
}

const VOICE_MARK = /\b(?:jarvis|javis|jarpis)\b[\s,.:;-]*(?:please\s+)?(?:mark|bookmark|flag)\s+(?:that|this|it)\b[\s,.:;—–-]*(.*)$/i;

/**
 * Marks spoken in the meeting ("Jarvis, mark that — price agreed") added to the phone's marks. A spoken mark
 * within 10 s of one that already exists (e.g. also tapped) is not added twice. Exported for tests.
 */
export function withVoiceMarks(marks: { t_s: number; label: string }[], lines: { t: number; text: string }[]) {
  const out = [...marks];
  for (const l of lines) {
    const m = l.text.match(VOICE_MARK);
    if (!m) continue;
    const t = Math.round(l.t);
    if (out.some((b) => Math.abs(b.t_s - t) <= 10)) continue;
    const label = (m[1] ?? "").replace(/[.!?]+$/, "").trim().slice(0, 120);
    out.push({ t_s: t, label: label || "Marked by voice" });
  }
  return out.sort((a, b) => a.t_s - b.t_s);
}

/** The whole meeting as one timeline: each chunk's lines shifted by the length of the chunks before it. */
async function meetingLines(db: SupabaseClient, recId: string) {
  const { data: chunks } = await db.from("recording_chunks").select("seq,duration_s").eq("recording_id", recId).order("seq");
  const offset = new Map<number, number>();
  let acc = 0;
  for (const c of chunks ?? []) { offset.set(c.seq, acc); acc += c.duration_s ?? 0; }
  const { data: segs } = await db.from("recording_segments").select("seq,idx,t_start_s,t_end_s,speaker,text")
    .eq("recording_id", recId).order("seq").order("idx").limit(20000);
  const lines = (segs ?? []).map((x) => {
    const o = offset.get(x.seq) ?? 0;
    return { t: o + Number(x.t_start_s), end: o + Number(x.t_end_s), speaker: x.speaker as string, text: x.text as string, seq: x.seq as number };
  });
  return { lines, chunks: chunks ?? [] };
}

// ── M4: summary, memory, push ────────────────────────────────────────────────────────────────────

/** Claimed (transcribed → summarising) so it runs once. */
async function summariseRecording(db: SupabaseClient, recId: string) {
  const staleBefore = new Date(Date.now() - STALE_MS).toISOString();
  const { data: claimed } = await db.from("recordings")
    .update({ status: "summarising", updated_at: now() })
    .eq("id", recId)
    .or(`status.eq.transcribed,and(status.eq.summarising,updated_at.lt.${staleBefore})`)
    .select("id,user_id,client_id,kind,title,started_at,ended_at,duration_s,bookmarks,speakers,memory_id,starred,error,audio_deleted_at,summary");
  const rec = claimed?.[0];
  if (!rec) return;
  const isVideo = rec.kind === "video" || rec.kind === "walk";
  const walk = rec.kind === "walk";
  try {
    const { lines } = await meetingLines(db, recId);
    const date = lagosDate(rec.started_at);
    const t0 = Date.now();
    // "Jarvis, mark that — price agreed" said during the meeting: the wake word is off while recording, so the
    // phone never hears it as a command — pick it out of the transcript and make it a real marked moment.
    const bookmarks = withVoiceMarks((rec.bookmarks ?? []) as { t_s: number; label: string }[], lines);
    // A title Law typed is kept; one the model made on an earlier run is replaced.
    const userTitle = rec.title?.trim() && rec.title !== rec.summary?.title ? rec.title.trim() : "";
    const s = isVideo
      ? await summariseVideo(lines as Line[], { walk, title: userTitle, date, durationS: rec.duration_s, bookmarks, userId: rec.user_id })
      : await summarise(lines as Line[], { title: userTitle, date, durationS: rec.duration_s, bookmarks, userId: rec.user_id });
    const title = (userTitle || s.title || (walk ? "Site walk" : isVideo ? "Video" : "Meeting")).slice(0, 200);
    s.title = title;
    // Names Law typed himself win over the model's guesses.
    const speakers = { ...Object.fromEntries(s.speakers.filter((x) => x.label && x.name).map((x) => [x.label, x.name])), ...(rec.speakers ?? {}) };
    const memoryId = await saveMemory(db, rec, s, date, isVideo ? { type: "video", heading: walk ? "Site walk" : "Video", tag: walk ? "walk" : "video" } : undefined);
    const days = await keepDays(db, rec.user_id);
    await db.from("recordings").update({
      status: "ready", summary: s, title, speakers, memory_id: memoryId, bookmarks,
      audio_expires_at: rec.audio_deleted_at ? null : expiryFor(rec.ended_at, days, rec.starred),
      error: rec.error?.includes("part(s)") ? rec.error : null, updated_at: now(),
    }).eq("id", recId);
    console.log(`meeting summary ${recId}: ${Date.now() - t0} ms, meeting=${s.is_meeting}, ${s.action_items.length} actions`);
    if (pushConfigured()) {
      await sendPush(rec.user_id, title, isVideo ? videoPushBody(s, walk) : pushBody(s), { kind: "meeting", id: rec.client_id })
        .catch((e) => console.warn(`meeting push: ${e instanceof Error ? e.message : e}`));
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.error(`meeting summary ${recId} failed: ${msg}`);
    // Back to "transcribed" so the sweep tries again; the transcript itself is safe.
    await db.from("recordings").update({ status: "transcribed", error: `summary: ${msg.slice(0, 400)}`, updated_at: now() }).eq("id", recId);
  }
}

/** The searchable memory (recall/chat find meetings through it). Updated in place; none for "no meeting". */
async function saveMemory(
  db: SupabaseClient,
  rec: { id: string; user_id: string; client_id: string; started_at: string; memory_id?: string | null },
  s: Summary,
  date: string,
  as: { type: string; heading: string; tag: string } = { type: "meeting", heading: "Meeting", tag: "meeting" },
): Promise<string | null> {
  let memoryId: string | null = rec.memory_id ?? null;
  if (!s.is_meeting) {
    if (memoryId) await db.from("memories").delete().eq("id", memoryId).eq("user_id", rec.user_id);
    return null;
  }
  const text = memoryText(s, date, as.heading);
  const row = { text, embedding: await embed(text), tags: [as.tag], metadata: { recording_id: rec.id, client_id: rec.client_id } };
  // memories.client_id is a UUID column → a stable UUID derived from the recording id (same on every re-run).
  const memClientId = await stableUuid(`meeting-${rec.client_id}`);
  if (!memoryId) { // a previous run may have saved it before failing (memories.client_id is unique when set)
    const { data: existing } = await db.from("memories").select("id").eq("client_id", memClientId).eq("user_id", rec.user_id).maybeSingle();
    memoryId = existing?.id ?? null;
  }
  if (memoryId) {
    await db.from("memories").update(row).eq("id", memoryId).eq("user_id", rec.user_id);
  } else {
    const { data: m, error } = await db.from("memories")
      .insert({ ...row, user_id: rec.user_id, type: as.type, created_at: rec.started_at, client_id: memClientId })
      .select("id").single();
    if (error) throw new Error(`memory: ${error.message}`);
    memoryId = m.id;
  }
  return memoryId;
}

// ── M6b: starred → Google Drive ─────────────────────────────────────────────────────────────────

const DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file";

/** Copy a starred, finished meeting (audio pieces + notes.txt) to Drive → JARVIS → Recordings → "<date> <title>". */
async function copyToDrive(db: SupabaseClient, recId: string) {
  const { data: rec } = await db.from("recordings")
    .select("id,user_id,client_id,title,started_at,status,starred,summary,audio_deleted_at,drive_status").eq("id", recId).maybeSingle();
  if (!rec || !rec.starred || rec.status !== "ready" || rec.drive_status === "copied" || rec.drive_status === "copying") return;
  const g = await connectionStatus(rec.user_id).catch(() => null);
  if (!g?.connected || !(g.scopes ?? []).includes(DRIVE_SCOPE)) {
    if (rec.drive_status !== "not_connected") await db.from("recordings").update({ drive_status: "not_connected" }).eq("id", recId);
    return;
  }
  const { data: claimed } = await db.from("recordings").update({ drive_status: "copying" }).eq("id", recId)
    .in("drive_status", ["", "not_connected", "failed"]).select("id");
  if (!claimed?.length) return;
  try {
    const date = new Date(rec.started_at).toISOString().slice(0, 10);
    const jarvis = await ensureFolder(rec.user_id, "JARVIS");
    const recordings = await ensureFolder(rec.user_id, "Recordings", jarvis);
    const folder = await ensureFolder(rec.user_id, `${date} ${(rec.title || "Meeting").replace(/[\\/:*?"<>|]/g, "-").slice(0, 80)}`, recordings);
    if (!rec.audio_deleted_at) {
      const { data: chunks } = await db.from("recording_chunks").select("seq,path").eq("recording_id", recId).order("seq");
      for (const c of chunks ?? []) {
        const { data: blob, error } = await db.storage.from(BUCKET).download(c.path);
        if (error || !blob) throw new Error(`download ${c.path}: ${error?.message ?? "missing"}`);
        await uploadFile(rec.user_id, folder, `part-${String(c.seq + 1).padStart(3, "0")}.m4a`, new Uint8Array(await blob.arrayBuffer()), "audio/mp4");
      }
    }
    const { lines } = await meetingLines(db, recId);
    const notes = (rec.summary ? memoryText(rec.summary as Summary, lagosDate(rec.started_at)) + "\n\n" : "") +
      "Transcript\n\n" + lines.map((l) => `[${new Date(l.t * 1000).toISOString().slice(11, 19)}] ${l.speaker}: ${l.text}`).join("\n");
    await uploadFile(rec.user_id, folder, "notes.txt", new TextEncoder().encode(notes), "text/plain");
    await db.from("recordings").update({ drive_status: "copied", drive_folder_id: folder, drive_copied_at: now() }).eq("id", recId);
    console.log(`meeting drive ${recId}: copied`);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.error(`meeting drive ${recId} failed: ${msg}`);
    await db.from("recordings").update({ drive_status: "failed" }).eq("id", recId);
  }
}

// ── M6: storage ──────────────────────────────────────────────────────────────────────────────────

/** Remove a recording's audio from storage; the transcript, summary and memory stay. */
async function deleteAudio(db: SupabaseClient, userId: string, clientId: string, recId?: string) {
  const prefix = `${userId}/${clientId}`;
  const { data: objs } = await db.storage.from(BUCKET).list(prefix, { limit: 1000 });
  if (objs?.length) await db.storage.from(BUCKET).remove(objs.map((o) => `${prefix}/${o.name}`));
  if (recId) await db.from("recordings").update({ audio_deleted_at: now(), audio_expires_at: null, audio_bytes: 0, updated_at: now() }).eq("id", recId);
}

/** Daily (pg_cron): delete audio whose time is up (not starred). */
async function retention(db: SupabaseClient) {
  const { data: due } = await db.from("recordings").select("id,user_id,client_id")
    .lt("audio_expires_at", now()).is("audio_deleted_at", null).eq("starred", false).limit(200);
  for (const r of due ?? []) await deleteAudio(db, r.user_id, r.client_id, r.id);
  if (due?.length) console.log(`meeting retention: audio deleted for ${due.length} recording(s)`);
  return { deleted: due?.length ?? 0 };
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
  // M6b: starred meetings not yet in Drive (cheap: copyToDrive checks the Google connection first).
  const { data: drive } = await db.from("recordings").select("id").eq("starred", true).eq("status", "ready")
    .in("drive_status", ["", "not_connected"]).limit(10);
  for (const r of drive ?? []) kick({ op: "work-drive", recording_id: r.id });
  return { chunks: chunks?.length ?? 0, recordings: recs?.length ?? 0, drive: drive?.length ?? 0 };
}

// ── request handling ─────────────────────────────────────────────────────────────────────────────

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  try {
    // Internal work (self-kicks and pg_cron): answer at once, do the work in this invocation.
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
        case "retention":
          return json(await retention(db));
        case "work-drive":
          EdgeRuntime.waitUntil(copyToDrive(db, String(b.recording_id)));
          return json({ accepted: true }, 202);
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
    const db = serviceClient();

    // Ops that aren't about one recording.
    if (op === "list") {
      const { data, error } = await db.from("recordings").select(LIST_FIELDS).eq("user_id", user.id).order("started_at", { ascending: false }).limit(500);
      if (error) return json({ error: error.message }, 500);
      const recs = data ?? [];
      return json({
        recordings: recs,
        keep_days: await keepDays(db, user.id),
        starred: recs.filter((r) => r.starred).length,
        audio_bytes: recs.filter((r) => !r.audio_deleted_at).reduce((a, r) => a + Number(r.audio_bytes ?? 0), 0),
      });
    }
    if (op === "settings") {
      let days = await keepDays(db, user.id);
      if (body.audio_keep_days != null) {
        const d = Number(body.audio_keep_days);
        if (![0, 30, 90, 365].includes(d)) return json({ error: "audio_keep_days must be 30, 90, 365 or 0 (forever)" }, 400);
        const { error } = await db.from("profile").upsert({ user_id: user.id, audio_keep_days: d, updated_at: now() }, { onConflict: "user_id" });
        if (error) return json({ error: error.message }, 500);
        days = d;
        // Re-date every finished recording whose audio is still there (starred ones keep theirs anyway).
        const { data: recs } = await db.from("recordings").select("id,ended_at,starred")
          .eq("user_id", user.id).eq("status", "ready").is("audio_deleted_at", null);
        for (const r of recs ?? []) {
          await db.from("recordings").update({ audio_expires_at: expiryFor(r.ended_at, d, r.starred) }).eq("id", r.id);
        }
      }
      return json({ audio_keep_days: days });
    }
    if (op === "costs") {
      const start = new Date();
      start.setUTCDate(1); start.setUTCHours(0, 0, 0, 0);
      const { data: calls } = await db.from("llm_calls").select("job,provider,model,usd_est,ok,tokens_in,tokens_out")
        .eq("user_id", user.id).gte("created_at", start.toISOString()).limit(20000);
      const byJob: Record<string, { calls: number; failed: number; usd: number; models: Record<string, number> }> = {};
      for (const c of calls ?? []) {
        const j = byJob[c.job] ??= { calls: 0, failed: 0, usd: 0, models: {} };
        j.calls++; if (!c.ok) j.failed++;
        j.usd += Number(c.usd_est ?? 0);
        if (c.ok) j.models[`${c.provider}/${c.model}`] = (j.models[`${c.provider}/${c.model}`] ?? 0) + 1;
      }
      const { data: routes } = await db.from("model_routes").select("job,routes,note");
      return json({
        since: start.toISOString(), jobs: byJob, total_usd: Object.values(byJob).reduce((a, j) => a + j.usd, 0), routes: routes ?? [],
        openrouter: !!Deno.env.get("OPENROUTER_API_KEY"),
      });
    }

    const clientId = String(body.client_id ?? "");
    if (!CLIENT_ID.test(clientId)) return json({ error: "bad client_id" }, 400);
    const findRec = async () =>
      (await db.from("recordings").select("id,status,chunks_total").eq("user_id", user.id).eq("client_id", clientId).maybeSingle()).data;

    switch (op) {
      case "chunk": {
        const name = String(body.name ?? "");
        if (!CHUNK_NAME.test(name)) return json({ error: "bad chunk name" }, 400);
        const seq = parseInt(name, 10);
        const path = `${user.id}/${clientId}/${name}`;

        // 1. The recording row (created by the first chunk).
        let rec = await findRec();
        if (!rec) {
          const startedAt = iso(body.started_at) ?? now();
          const { data, error } = await db.from("recordings").upsert({
            user_id: user.id, client_id: clientId, title: String(body.title ?? "").slice(0, 200),
            kind: String(body.kind ?? "meeting"), source: body.source === "glasses" ? "glasses" : "phone", started_at: startedAt, status: "uploading",
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
          audio_bytes: total, updated_at: now(),
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
          ended_at: iso(body.ended_at) ?? now(), bookmarks,
          ...(body.title ? { title: String(body.title).slice(0, 200) } : {}),
          ...(rec.status === "recording" || rec.status === "uploading" || rec.status === "uploaded" ? { status } : {}),
          updated_at: now(),
        }).eq("id", rec.id);
        if (error) return json({ error: error.message }, 500);
        if (status === "uploaded") {
          status = rec.status === "ready" ? "ready" : "transcribing";
          await processPending(db, rec.id); // also retries a failed summary
        }
        return json({ status, uploaded });
      }

      case "get": {
        const { data: rec } = await db.from("recordings").select(LIST_FIELDS).eq("user_id", user.id).eq("client_id", clientId).maybeSingle();
        if (!rec) return json({ error: "not found" }, 404);
        const { lines, chunks } = await meetingLines(db, rec.id);
        // Video key moments on the whole video's timeline (each piece's moments + the pieces before it).
        const { data: mom } = await db.from("recording_chunks").select("seq,duration_s,moments").eq("recording_id", rec.id).order("seq");
        let off = 0;
        const moments: { t: number; why: string }[] = [];
        for (const c of mom ?? []) {
          for (const m of (c.moments ?? []) as { t: number; why: string }[]) moments.push({ t: off + Number(m.t), why: m.why });
          off += c.duration_s ?? 0;
        }
        return json({ recording: rec, lines, chunks, moments });
      }

      case "frames": {
        // Phase 3: photos the phone took from the ORIGINAL video at the key moments (already in the media bucket).
        const frames = Array.isArray(body.frames)
          ? body.frames.slice(0, 40).map((f: { t?: unknown; path?: unknown; why?: unknown }) => ({
            t: Number(f.t) || 0, path: String(f.path ?? ""), why: String(f.why ?? "").slice(0, 200),
          })).filter((f: { path: string }) => f.path.startsWith(`${user.id}/`))
          : [];
        const { error } = await db.from("recordings").update({ frames, updated_at: now() }).eq("user_id", user.id).eq("client_id", clientId);
        if (error) return json({ error: error.message }, 500);
        return json({ ok: true, frames: frames.length });
      }

      case "audio-urls": {
        const { data: rec } = await db.from("recordings").select("id,audio_deleted_at").eq("user_id", user.id).eq("client_id", clientId).maybeSingle();
        if (!rec) return json({ error: "not found" }, 404);
        if (rec.audio_deleted_at) return json({ urls: [], deleted: true });
        const { data: chunks } = await db.from("recording_chunks").select("seq,path,duration_s").eq("recording_id", rec.id).order("seq");
        const { data: signed, error } = await db.storage.from(BUCKET).createSignedUrls((chunks ?? []).map((c) => c.path), 3600);
        if (error) return json({ error: error.message }, 500);
        return json({ urls: (chunks ?? []).map((c, i) => ({ seq: c.seq, url: signed?.[i]?.signedUrl ?? "", duration_s: c.duration_s })) });
      }

      case "star": {
        const starred = body.starred === true;
        const { data: rec } = await db.from("recordings").select("id,ended_at,audio_deleted_at,status").eq("user_id", user.id).eq("client_id", clientId).maybeSingle();
        if (!rec) return json({ error: "not found" }, 404);
        const expires = rec.audio_deleted_at || rec.status !== "ready" ? null : expiryFor(rec.ended_at, await keepDays(db, user.id), starred);
        await db.from("recordings").update({ starred, audio_expires_at: expires, updated_at: now() }).eq("id", rec.id);
        if (starred) kick({ op: "work-drive", recording_id: rec.id });
        return json({ ok: true, starred, audio_expires_at: expires });
      }

      case "title": {
        const title = String(body.title ?? "").trim().slice(0, 200);
        if (!title) return json({ error: "title is empty" }, 400);
        const { data: rec } = await db.from("recordings").select("id,user_id,client_id,started_at,summary,memory_id")
          .eq("user_id", user.id).eq("client_id", clientId).maybeSingle();
        if (!rec) return json({ error: "not found" }, 404);
        const summary = rec.summary ? { ...rec.summary, title } : null;
        await db.from("recordings").update({ title, ...(summary ? { summary } : {}), updated_at: now() }).eq("id", rec.id);
        if (summary && rec.memory_id) await saveMemory(db, rec, summary as Summary, lagosDate(rec.started_at));
        return json({ ok: true, title });
      }

      case "speaker": {
        const label = String(body.label ?? "").trim().slice(0, 60);
        const name = String(body.name ?? "").trim().slice(0, 60);
        if (!label) return json({ error: "label is required" }, 400);
        const { data: rec } = await db.from("recordings").select("id,speakers").eq("user_id", user.id).eq("client_id", clientId).maybeSingle();
        if (!rec) return json({ error: "not found" }, 404);
        const speakers = { ...(rec.speakers ?? {}) } as Record<string, string>;
        if (name) speakers[label] = name; else delete speakers[label];
        await db.from("recordings").update({ speakers, updated_at: now() }).eq("id", rec.id);
        return json({ ok: true, speakers });
      }

      case "tick": {
        const index = Number(body.index);
        if (!Number.isInteger(index) || index < 0 || index > 500) return json({ error: "bad index" }, 400);
        const { data: rec } = await db.from("recordings").select("id,action_done").eq("user_id", user.id).eq("client_id", clientId).maybeSingle();
        if (!rec) return json({ error: "not found" }, 404);
        const set = new Set<number>((rec.action_done ?? []) as number[]);
        if (body.done === false) set.delete(index); else set.add(index);
        const action_done = [...set].sort((a, b) => a - b);
        await db.from("recordings").update({ action_done, updated_at: now() }).eq("id", rec.id);
        return json({ ok: true, action_done });
      }

      case "delete-audio": {
        const rec = await findRec();
        if (!rec) return json({ error: "not found" }, 404);
        await deleteAudio(db, user.id, clientId, rec.id);
        return json({ ok: true });
      }

      case "ask": {
        const question = String(body.question ?? "").trim().slice(0, 500);
        if (!question) return json({ error: "question is empty" }, 400);
        const { data: rec } = await db.from("recordings").select("id,title,started_at,speakers").eq("user_id", user.id).eq("client_id", clientId).maybeSingle();
        if (!rec) return json({ error: "not found" }, 404);
        const { lines } = await meetingLines(db, rec.id);
        if (!lines.length) return json({ answer: "There's no transcript for this meeting yet.", moments: [] });
        // Show renamed speakers by name in what the model reads.
        const sp = (rec.speakers ?? {}) as Record<string, string>;
        const named: Line[] = lines.map((l) => ({ t: l.t, speaker: sp[l.speaker] ?? l.speaker, text: l.text }));
        const r = await askMeeting(named, question, { title: rec.title, date: lagosDate(rec.started_at), userId: user.id });
        return json(r);
      }

      case "delete": {
        const { data: rec } = await db.from("recordings").select("id,memory_id,frames").eq("user_id", user.id).eq("client_id", clientId).maybeSingle();
        await deleteAudio(db, user.id, clientId);
        // "Everything" includes a video's key-moment photos (media bucket; only this user's own paths).
        const framePaths = ((rec?.frames ?? []) as { path?: string }[]).map((f) => String(f.path ?? "")).filter((p) => p.startsWith(`${user.id}/`));
        if (framePaths.length) await db.storage.from("media").remove(framePaths);
        if (rec?.memory_id) await db.from("memories").delete().eq("id", rec.memory_id).eq("user_id", user.id);
        await db.from("recordings").delete().eq("user_id", user.id).eq("client_id", clientId);
        return json({ ok: true });
      }

      default:
        return json({ error: "unknown op" }, 400);
    }
  } catch (e) {
    console.error("meeting error:", e);
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});

/** Start a transcription for every chunk of [recId] that still needs one; then check whether it's complete. */
async function processPending(db: SupabaseClient, recId: string) {
  const { data: chunks } = await db.from("recording_chunks").select("seq,status,attempts").eq("recording_id", recId).order("seq");
  for (const c of chunks ?? []) {
    if (c.status !== "done" && c.attempts < MAX_ATTEMPTS) kick({ op: "work-chunk", recording_id: recId, seq: c.seq });
  }
  await maybeTranscribed(db, recId);
}
