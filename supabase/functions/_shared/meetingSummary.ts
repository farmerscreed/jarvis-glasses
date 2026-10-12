// Phase 2 · M4 — turn a meeting transcript into a structured summary (+ speaker names) with Claude.
// Pure function of (transcript, context) → summary, so it can be tested alone. The model router (M7)
// will later pick a cheaper model; MEETING_SUMMARY_MODEL overrides today (falls back to CLAUDE_MODEL).
import { claudeJson } from "./anthropic.ts";

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
- Don't assume which speaker is Law unless the transcript makes it clear; otherwise refer to them by label.
- If something is garbled, write [unclear] rather than guessing.
- Lines labelled "Media" are a TV/video/music in the room, not participants — ignore their content unless people discuss it.
- If the recording contains no real conversation (only media, silence, or test noise), set is_meeting=false, say so plainly in in_short, and leave the lists empty.
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
  ctx: { title?: string; date?: string; durationS?: number; bookmarks?: { t_s: number; label: string }[] },
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
  return await claudeJson<Summary>(
    SYSTEM,
    `${header}\n\nTranscript:\n${text || "(no speech was transcribed)"}`,
    SCHEMA,
    { model: Deno.env.get("MEETING_SUMMARY_MODEL") ?? undefined, maxTokens: 4000 },
  );
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
  return `Summary ready: ${n(s.action_items.length, "action item")}, ${n(s.decisions.length, "decision")}.`;
}
