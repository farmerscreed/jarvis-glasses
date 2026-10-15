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
  const system = `You answer the owner's question about ONE recorded meeting, using only its transcript (Nigeria; translations in [brackets]; "Media" lines are a TV/radio, not people).
- Answer in 1–4 plain sentences, quoting amounts, dates and names exactly as said.
- If the transcript doesn't say, answer "The meeting doesn't say." — never guess.
- moments: up to 3 lines that support the answer: t = the [mm:ss] time in seconds, quote = the words.`;
  const user = `Meeting: ${ctx.title || "Meeting"}${ctx.date ? ` (${ctx.date})` : ""}${names ? `\nSpeaker names: ${names}` : ""}\n\nTranscript:\n${text}\n\nQuestion: ${question}`;
  const r = await llmJson<{ answer: string; moments: { t: number; quote: string }[] }>("meeting_ask", system, user, ASK_SCHEMA, { userId: ctx.userId });
  return { answer: r.answer, moments: r.moments ?? [] };
}

/** The meeting memory: what recall/chat search over ("what did Mr Ade say about delivery?"). */
export function memoryText(s: Summary, date: string): string {
  const parts = [`Meeting: ${s.title} — ${date}`, `In short: ${s.in_short}`];
  if (s.decisions.length) parts.push("Decisions:\n" + s.decisions.map((d) => `- ${d}`).join("\n"));
  if (s.action_items.length) {
    parts.push("Action items:\n" + s.action_items.map((a) =>
      `- ${a.task}${a.owner || a.due ? ` (${[a.owner, a.due].filter(Boolean).join(", ")})` : ""}`).join("\n"));
  }
  if (s.numbers.length) parts.push("Numbers:\n" + s.numbers.map((n) => `- ${n.label}: ${n.value}`).join("\n"));
  if (s.people.length) parts.push("People: " + s.people.join(", "));
  if (s.follow_ups.length) parts.push("Follow-ups:\n" + s.follow_ups.map((f) => `- ${f}`).join("\n"));
  if (s.open_questions.length) parts.push("Open questions:\n" + s.open_questions.map((q) => `- ${q}`).join("\n"));
  return parts.join("\n\n");
}

/** "Site meeting with Mr Ade — summary ready: 3 action items, 2 decisions." */
export function pushBody(s: Summary): string {
  if (!s.is_meeting) return "Recording processed — no conversation found in it.";
  const n = (k: number, w: string) => `${k} ${w}${k === 1 ? "" : "s"}`;
  if (!s.action_items.length && !s.decisions.length) return "Notes ready — tap to read the summary.";
  return `Summary ready: ${n(s.action_items.length, "action item")}, ${n(s.decisions.length, "decision")}.`;
}
