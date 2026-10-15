// Phase 3 · V3 — Gemini watches one video piece (≤ 3 min, 480p analysis copy) and returns what was SAID
// (speaker lines, like meetings), what was SEEN (timestamped notes) and KEY MOMENTS (times worth a photo).
// Video goes through the Gemini Files API (too big to send inline). Every step is capped so the whole job fits
// one function invocation (~150 s); the sweep retries a piece that didn't make it.
import { mergeFragments, parseTime, Segment } from "./meetingStt.ts";

export type Moment = { t: number; why: string };
export type VideoResult = { segments: Segment[]; moments: Moment[]; model: string; ms: number; tokensIn?: number; tokensOut?: number };

const API = "https://generativelanguage.googleapis.com";
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function prompt(kind: string, vocab: string): string {
  const walk = kind === "walk";
  return `You are watching a ${walk ? "SITE WALK" : "video"} filmed by the owner${walk ? " walking around a building site or farm" : ""} on smart glasses or a phone (Nigeria; Nigerian English, sometimes Pidgin/Yoruba/Igbo/Hausa — give an English translation in [brackets] for non-English speech).
Return three lists, times as "MM:SS" from the start of THIS clip:
- said: what people said, one entry per sentence or short turn; speakers "Speaker 1", "Speaker 2"… (the person filming is usually the closest, loudest voice). A TV/radio/music → speaker "Media". If the owner talks to the assistant ("Jarvis, …") keep it.
- seen: what the camera shows, as short factual notes every time something new or important comes into view — places, rooms, materials, equipment, work done or not done, damage or problems, safety issues, quantities you can actually count (say "about" when unsure), readable signs/labels/numbers. ${walk ? "Be thorough: this becomes a site report." : "Keep it brief."}
- moments: up to ${walk ? 10 : 5} times that would make the best still photos of something important (a clear view, not blurred), with why.
Rules: only what is actually seen or heard; never invent; "[unclear]" for unclear words; empty lists are fine.
Common words: Jarvis, naira, Port Harcourt, Lagos, site, block, cement, plaster, rebar, pepper, greenhouse${vocab ? ", " + vocab : ""}.`;
}

const TIMED_TEXT = (k: string) => ({
  type: "OBJECT", properties: { start: { type: "STRING" }, end: { type: "STRING" }, speaker: { type: "STRING" }, [k]: { type: "STRING" } },
  required: ["start", "end", "speaker", k],
});
const SCHEMA = {
  type: "OBJECT",
  properties: {
    said: { type: "ARRAY", items: TIMED_TEXT("text") },
    seen: { type: "ARRAY", items: { type: "OBJECT", properties: { t: { type: "STRING" }, what: { type: "STRING" } }, required: ["t", "what"] } },
    moments: { type: "ARRAY", items: { type: "OBJECT", properties: { t: { type: "STRING" }, why: { type: "STRING" } }, required: ["t", "why"] } },
  },
  required: ["said", "seen", "moments"],
};

async function fetchCapped(url: string, init: RequestInit, ms: number): Promise<Response> {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), ms);
  try {
    return await fetch(url, { ...init, signal: ctl.signal });
  } finally {
    clearTimeout(timer);
  }
}

/** Upload to the Gemini Files API (resumable, one shot) → the file's name + uri once it's ACTIVE. */
async function uploadFile(bytes: Uint8Array, mime: string, apiKey: string, deadline: number): Promise<{ name: string; uri: string }> {
  const start = await fetchCapped(`${API}/upload/v1beta/files`, {
    method: "POST",
    headers: {
      "x-goog-api-key": apiKey, "X-Goog-Upload-Protocol": "resumable", "X-Goog-Upload-Command": "start",
      "X-Goog-Upload-Header-Content-Length": String(bytes.length), "X-Goog-Upload-Header-Content-Type": mime,
      "content-type": "application/json",
    },
    body: JSON.stringify({ file: { display_name: `jarvis-${Date.now()}` } }),
  }, 20_000);
  const uploadUrl = start.headers.get("x-goog-upload-url");
  if (!start.ok || !uploadUrl) throw new Error(`files start ${start.status}: ${(await start.text()).slice(0, 200)}`);
  const up = await fetchCapped(uploadUrl, {
    method: "POST",
    headers: { "X-Goog-Upload-Command": "upload, finalize", "X-Goog-Upload-Offset": "0", "content-length": String(bytes.length) },
    body: new Blob([bytes as Uint8Array<ArrayBuffer>]),
  }, 60_000);
  const upText = await up.text();
  if (!up.ok) throw new Error(`files upload ${up.status}: ${upText.slice(0, 200)}`);
  let file = JSON.parse(upText).file as { name: string; uri: string; state: string };
  // Video needs processing before it can be used.
  while (file.state === "PROCESSING") {
    if (Date.now() > deadline) throw new Error("video still processing at Gemini — will retry");
    await sleep(2_000);
    const r = await fetchCapped(`${API}/v1beta/${file.name}`, { headers: { "x-goog-api-key": apiKey } }, 10_000);
    if (r.ok) file = await r.json();
  }
  if (file.state !== "ACTIVE") throw new Error(`video not usable at Gemini (state ${file.state})`);
  return { name: file.name, uri: file.uri };
}

export async function analyseVideoChunk(
  video: Uint8Array,
  opts: { apiKey: string; models: string[]; kind: string; vocab?: string; mimeType?: string },
): Promise<VideoResult> {
  const t0 = Date.now();
  const mime = opts.mimeType ?? "video/mp4";
  const file = await uploadFile(video, mime, opts.apiKey, t0 + 70_000);
  const body = JSON.stringify({
    contents: [{ parts: [{ text: prompt(opts.kind, opts.vocab ?? "") }, { file_data: { mime_type: mime, file_uri: file.uri } }] }],
    generationConfig: { temperature: 0, maxOutputTokens: 16384, responseMimeType: "application/json", responseSchema: SCHEMA, thinkingConfig: { thinkingBudget: 0 } },
  });
  let lastErr = "";
  try {
    for (const [mi, model] of opts.models.entries()) {
      const left = 135_000 - (Date.now() - t0);
      if (left < 20_000) break;
      let res: Response;
      try {
        res = await fetchCapped(`${API}/v1beta/models/${model}:generateContent`, {
          method: "POST", headers: { "content-type": "application/json", "x-goog-api-key": opts.apiKey }, body,
        }, Math.min(left - 5_000, mi === opts.models.length - 1 ? 100_000 : 70_000));
      } catch (e) {
        lastErr = `${model}: ${e instanceof Error && e.name === "AbortError" ? "too slow" : String(e)}`;
        continue;
      }
      const text = await res.text();
      if (!res.ok) { lastErr = `${model} ${res.status}: ${text.slice(0, 200)}`; continue; }
      const data = JSON.parse(text);
      const out = (data.candidates?.[0]?.content?.parts ?? []).map((p: { text?: string }) => p.text ?? "").join("");
      let parsed: { said?: Record<string, unknown>[]; seen?: Record<string, unknown>[]; moments?: Record<string, unknown>[] };
      try { parsed = JSON.parse(out || "{}"); } catch { lastErr = `${model}: unparseable (${data.candidates?.[0]?.finishReason})`; continue; }
      const said = mergeFragments((parsed.said ?? []).map((s) => ({
        start: parseTime(s.start), end: parseTime(s.end), speaker: String(s.speaker ?? "Speaker").slice(0, 60), text: String(s.text ?? "").trim(),
      })).filter((s) => s.text));
      const seen: Segment[] = (parsed.seen ?? []).map((s) => {
        const t = parseTime(s.t);
        return { start: t, end: t, speaker: "Seen", text: String(s.what ?? "").trim() };
      }).filter((s) => s.text);
      const empty = !said.length && !seen.length;
      if (empty && mi < opts.models.length - 1) { lastErr = `${model}: empty`; continue; } // a clip with nothing at all is suspicious
      const u = data.usageMetadata ?? {};
      return {
        segments: [...said, ...seen].sort((a, b) => a.start - b.start),
        moments: (parsed.moments ?? []).map((m) => ({ t: parseTime(m.t), why: String(m.why ?? "").slice(0, 200) })),
        model, ms: Date.now() - t0, tokensIn: u.promptTokenCount, tokensOut: u.candidatesTokenCount,
      };
    }
    throw new Error(`video analysis failed: ${lastErr}`);
  } finally {
    // Don't leave the copy at Google (it would expire in 48 h anyway).
    fetchCapped(`${API}/v1beta/${file.name}`, { method: "DELETE", headers: { "x-goog-api-key": opts.apiKey } }, 10_000).catch(() => {});
  }
}
