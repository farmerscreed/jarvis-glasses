// Phase 2 · M4 — turn a meeting transcript into a structured summary (+ speaker names), and (M5) answer
// questions about one meeting. Models come from the switchboard (M7, _shared/llm.ts): jobs "meeting_summary"
// and "meeting_ask" in the model_routes table.
import { llmJson, RouteEntry } from "./llm.ts";

export type Line = { t: number; speaker: string; text: string };
export type Summary = {
  is_meeting: boolean;
  title: string;
  in_short: string;
  decisions: string[];
  action_items: { task: string; owner: string; due: string }[];
  numbers: { label: string; value: string }[];
  people: string[];
  follow_ups: string[];
  open_questions: string[];
  speakers: { label: string; name: string }[];
  /** Phase 3 · V6: only for a site walk — the structured report (times in seconds from the start of the walk). */
  report?: WalkReport;
};

export type WalkReport = {
  place: string;
  overview: string;
  progress: string[];
  problems: { what: string; where: string; severity: "high" | "medium" | "low"; t: number }[];
  counts: { item: string; number: string; sure: string }[];
  safety: string[];
  next_steps: string[];
  questions: string[];
};

const str = { type: "string" };
const arr = (items: Record<string, unknown>) => ({ type: "array", items });
const obj = (props: Record<string, unknown>) => ({
  type: "object", properties: props, required: Object.keys(props), additionalProperties: false,
});
const SCHEMA = obj({
  is_meeting: { type: "boolean" },
  title: str,
  in_short: str,
  decisions: arr(str),
  action_items: arr(obj({ task: str, owner: str, due: str })),
  numbers: arr(obj({ label: str, value: str })),
  people: arr(str),
  follow_ups: arr(str),
  open_questions: arr(str),
  speakers: arr(obj({ label: str, name: str })),
});

const num = { type: "number" };
const WALK_SCHEMA = (() => {
  const base = JSON.parse(JSON.stringify(SCHEMA)) as { properties: Record<string, unknown>; required: string[] };
  base.properties.report = obj({
    place: str,
    overview: str,
    progress: arr(str),
    problems: arr(obj({ what: str, where: str, severity: { type: "string", enum: ["high", "medium", "low"] }, t: num })),
    counts: arr(obj({ item: str, number: str, sure: str })),
    safety: arr(str),
    next_steps: arr(str),
    questions: arr(str),
  });
  base.required = [...base.required, "report"];
  return base;
})();

const SYSTEM = `You write the notes for an in-person business meeting recorded on the owner's phone (Nigeria; Nigerian English, sometimes Pidgin/Yoruba/Igbo/Hausa — translations are in [brackets]). The owner is Law; he wears AI glasses and the assistant is called JARVIS.
Strict rules:
- Use ONLY what is in the transcript. Never invent names, owners, dates, amounts or decisions.
- An action item's owner/due: only if someone said it — otherwise "".
- Numbers: quote money (naira), quantities, dates and measurements exactly as spoken, with what they refer to. Never calculate totals or figures nobody said.
- Relative dates ("next Tuesday") may be resolved against the meeting date — show both, e.g. "next Tuesday (20 Oct)".
- Don't assume which speaker is Law unless the transcript makes it clear; otherwise refer to them by label only — never write guesses like "(likely Law)".
- Lines where someone tells JARVIS to "mark that"/"bookmark this" are commands to the recording app, already saved as marked moments — never list them as action items or decisions.
- If something is garbled, write [unclear] rather than guessing.
- Lines labelled "Media" are a TV/video/music in the room, not participants — ignore their content unless people discuss it.
- is_meeting=false ONLY when there is no real conversation between people at all (just a TV/video/music, silence or test noise) — then say so plainly in in_short and leave the lists empty.
- ANY real conversation counts, even an informal chat with no agenda: is_meeting=true, in_short = what was talked about and any views or plans people expressed (neutral, no judgement), people = who took part or was mentioned; decisions/action items only if there were any (empty lists are fine). The title names the main topics, e.g. "Chat about COVID's origins and Gaddafi".
- speakers: map labels ("Speaker 1") to a name ONLY when the transcript makes it clear (e.g. "Thank you, Mr Ade" said to them, or they introduce themselves). Otherwise leave that label out.
- title: short and specific, e.g. "Site meeting with Mr Ade — cement delivery" (keep the user's title if one is given and still fits).
- in_short: 2–4 plain sentences a busy person can read in 10 seconds.
- Be brief: each list item one short line. Write in plain English.`;

/** Lines as "[mm:ss] Speaker: text", with the user's marked moments. */
export function transcriptText(lines: Line[], bookmarks: { t_s: number; label: string }[] = []): string {
  const mmss = (t: number) => {
    const s = Math.max(0, Math.round(t));
    return s >= 3600
      ? `${Math.floor(s / 3600)}:${String(Math.floor(s / 60) % 60).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`
      : `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
  };
  const body = lines.map((l) => `[${mmss(l.t)}] ${l.speaker}: ${l.text}`).join("\n");
  const marks = bookmarks.length
    ? "\n\nMoments the owner marked as important:\n" + bookmarks.map((b) => `[${mmss(b.t_s)}] ${b.label || "(marked)"}`).join("\n")
    : "";
  return body + marks;
}

/** Phase 3: the same notes shape for a VIDEO (what was seen + said) or a SITE WALK (report-style). */
const VIDEO_SYSTEM = (walk: boolean) => `You write the notes for a ${walk ? "SITE WALK video (the owner walked a building site or farm, filming and talking)" : "video the owner filmed"} on smart glasses or a phone (Nigeria). The owner is Law; the assistant is JARVIS. The input has two kinds of lines: what people SAID ("Speaker 1: …", translations in [brackets]) and what the camera showed ("Seen: …").
Strict rules:
- Use ONLY what was seen or said. Never invent. Counts from video are approximate — keep "about"/"≈" when the input says so.
- is_meeting here means "has content worth keeping" (the field is shared with meetings): true for ANY real video or site walk
  that shows or says something; false ONLY if the video has nothing meaningful (blank, pocket, floor).
- in_short: what the video shows and what was said about it, 2–4 plain sentences.
- decisions/action_items: only if someone said them${walk ? " — for a site walk, also list clear problems that need fixing as action items (owner only if named)" : ""}.
- numbers: quantities, measurements, prices, dates — seen or said, with what they refer to.
- people: who was there or named. follow_ups: things to check next. open_questions: what was unclear.
- speakers: label → name only when clear. Lines where the owner tells JARVIS to "mark that" are commands, not action items.
- title: short and specific, e.g. "${walk ? "Site walk — block C, plastering and rebar" : "Pepper greenhouse — leaf damage"}".${walk ? `
- report (the site report Law sends to people — plain, factual, no padding):
  place: the site/farm/building if said or readable (else ""); overview: 2–3 sentences on the state of the site;
  progress: work done or under way, one line each; problems: each a defect/delay/risk SEEN or SAID — what, where (block,
  room, area as said/seen, else ""), severity (high = safety or stops work, medium = needs fixing soon, low = minor) and t =
  the time in SECONDS from the start where it is best seen (from the [mm:ss] of the line that shows it, e.g. [01:05] → 65);
  counts: things counted — number exactly as said, or "about N" if estimated from the video, sure = "said" | "counted" |
  "estimate"; safety: hazards (no helmets, open edges, exposed wiring…); next_steps: only what someone said or what a listed
  problem plainly needs; questions: what's unclear. Empty lists are fine. Never invent problems.` : ""}`;

export async function summariseVideo(
  lines: Line[],
  ctx: { walk: boolean; title?: string; date?: string; durationS?: number; bookmarks?: { t_s: number; label: string }[]; userId?: string },
): Promise<Summary> {
  let text = transcriptText(lines, ctx.bookmarks ?? []);
  if (text.length > 300_000) text = text.slice(0, 150_000) + "\n[…]\n" + text.slice(-150_000);
  const header = [
    ctx.title ? `Title given by the owner: ${ctx.title}` : "No title given.",
    ctx.date ? `Date: ${ctx.date}` : "",
    ctx.durationS ? `Length: ${Math.max(1, Math.round(ctx.durationS / 60))} minutes` : "",
  ].filter(Boolean).join("\n");
  const s = await llmJson<Summary>(
    ctx.walk ? "walk_report" : "video_summary", VIDEO_SYSTEM(ctx.walk), `${header}\n\nWhat was seen and said:\n${text || "(nothing was seen or heard)"}`, ctx.walk ? WALK_SCHEMA : SCHEMA, { userId: ctx.userId },
  );
  delete s._model;
  // Safety net: a video with real seen/said content is never "nothing to note" (that would also delete its memory).
  if (!s.is_meeting && lines.filter((l) => l.text.trim().length > 15).length >= 3) s.is_meeting = true;
  return s;
}

/** Push text for a video / walk. */
export function videoPushBody(s: Summary, walk: boolean): string {
  if (!s.is_meeting) return "Video processed — nothing to note in it.";
  if (walk) {
    const n = s.report?.problems.length ?? s.action_items.length;
    const done = s.report?.progress.length ?? 0;
    return `Site walk report ready: ${n} problem${n === 1 ? "" : "s"}${done ? `, ${done} thing${done === 1 ? "" : "s"} done` : ""}.`;
  }
  return "Video notes ready — tap to read what was seen and said.";
}

export async function summarise(
  lines: Line[],
  ctx: { title?: string; date?: string; durationS?: number; bookmarks?: { t_s: number; label: string }[]; userId?: string; routes?: RouteEntry[] },
): Promise<Summary> {
  // ~2 h of talk is ~150k characters; beyond that keep the start and the end (rare; block-summaries later).
  let text = transcriptText(lines, ctx.bookmarks ?? []);
  const MAX = 300_000;
  if (text.length > MAX) text = text.slice(0, MAX / 2) + "\n[… middle of a very long meeting omitted …]\n" + text.slice(-MAX / 2);
  const header = [
    ctx.title ? `Title given by the owner: ${ctx.title}` : "No title given.",
    ctx.date ? `Date: ${ctx.date}` : "",
    ctx.durationS ? `Length: ${Math.round(ctx.durationS / 60)} minutes` : "",
  ].filter(Boolean).join("\n");
  const s = await llmJson<Summary>(
    "meeting_summary", SYSTEM, `${header}\n\nTranscript:\n${text || "(no speech was transcribed)"}`, SCHEMA, { userId: ctx.userId, routes: ctx.routes },
  );
  delete s._model;
  return s;
}

const ASK_SCHEMA = obj({ answer: str, moments: arr(obj({ t: { type: "number" }, quote: str })) });

/** M5 "Ask about this meeting": an answer from THIS meeting's transcript only, with the moments it used. */
export async function askMeeting(
  lines: Line[],
  question: string,
  ctx: { title?: string; date?: string; speakers?: Record<string, string>; userId?: string },
): Promise<{ answer: string; moments: { t: number; quote: string }[] }> {
  let text = transcriptText(lines);
  if (text.length > 300_000) text = text.slice(0, 150_000) + "\n[…]\n" + text.slice(-150_000);
  const names = Object.entries(ctx.speakers ?? {}).map(([k, v]) => `${k} = ${v}`).join(", ");
  const system = `You answer the owner's question about ONE recording — a meeting, or a video (then "Seen" lines are what the camera showed, the rest is what was said) — using only its transcript (Nigeria; translations in [brackets]; "Media" lines are a TV/radio, not people).
- Answer in 1–4 plain sentences, quoting amounts, dates and names exactly as said.
- If the transcript doesn't say, answer "The recording doesn't say." — never guess. Counts from video are approximate: say "about".
- moments: up to 3 lines that support the answer: t = the [mm:ss] time in seconds, quote = the words.`;
  const user = `Meeting: ${ctx.title || "Meeting"}${ctx.date ? ` (${ctx.date})` : ""}${names ? `\nSpeaker names: ${names}` : ""}\n\nTranscript:\n${text}\n\nQuestion: ${question}`;
  const r = await llmJson<{ answer: string; moments: { t: number; quote: string }[] }>("meeting_ask", system, user, ASK_SCHEMA, { userId: ctx.userId });
  return { answer: r.answer, moments: r.moments ?? [] };
}

/** The meeting memory: what recall/chat search over ("what did Mr Ade say about delivery?"). */
export function memoryText(s: Summary, date: string, heading = "Meeting"): string {
  const parts = [`${heading}: ${s.title} — ${date}`, `In short: ${s.in_short}`];
  if (s.decisions.length) parts.push("Decisions:\n" + s.decisions.map((d) => `- ${d}`).join("\n"));
  if (s.action_items.length) {
    parts.push("Action items:\n" + s.action_items.map((a) =>
      `- ${a.task}${a.owner || a.due ? ` (${[a.owner, a.due].filter(Boolean).join(", ")})` : ""}`).join("\n"));
  }
  if (s.numbers.length) parts.push("Numbers:\n" + s.numbers.map((n) => `- ${n.label}: ${n.value}`).join("\n"));
  if (s.people.length) parts.push("People: " + s.people.join(", "));
  if (s.follow_ups.length) parts.push("Follow-ups:\n" + s.follow_ups.map((f) => `- ${f}`).join("\n"));
  if (s.open_questions.length) parts.push("Open questions:\n" + s.open_questions.map((q) => `- ${q}`).join("\n"));
  const r = s.report;
  if (r) {
    if (r.place) parts.push(`Place: ${r.place}`);
    if (r.progress.length) parts.push("Progress:\n" + r.progress.map((x) => `- ${x}`).join("\n"));
    if (r.problems.length) parts.push("Problems:\n" + r.problems.map((x) => `- [${x.severity}] ${x.what}${x.where ? ` (${x.where})` : ""}`).join("\n"));
    if (r.counts.length) parts.push("Counts:\n" + r.counts.map((x) => `- ${x.item}: ${x.number} (${x.sure})`).join("\n"));
    if (r.safety.length) parts.push("Safety:\n" + r.safety.map((x) => `- ${x}`).join("\n"));
    if (r.next_steps.length) parts.push("Next steps:\n" + r.next_steps.map((x) => `- ${x}`).join("\n"));
  }
  return parts.join("\n\n");
}

/** "Site meeting with Mr Ade — summary ready: 3 action items, 2 decisions." */
export function pushBody(s: Summary): string {
  if (!s.is_meeting) return "Recording processed — no conversation found in it.";
  const n = (k: number, w: string) => `${k} ${w}${k === 1 ? "" : "s"}`;
  if (!s.action_items.length && !s.decisions.length) return "Notes ready — tap to read the summary.";
  return `Summary ready: ${n(s.action_items.length, "action item")}, ${n(s.decisions.length, "decision")}.`;
}
