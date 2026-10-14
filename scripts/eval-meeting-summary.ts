// Phase 2 · M7 — compare meeting-summary models side by side before switching the `meeting_summary` route.
//
// Usage (PC, from the repo root; keys in supabase/functions/.env or the environment):
//   deno run -A scripts/eval-meeting-summary.ts \
//     --models "anthropic:default,openrouter:deepseek/deepseek-chat,openrouter:moonshotai/kimi-k2,gemini:gemini-2.5-flash" \
//     rec-1791838770824-c4b4a4 rec-...            (recording ids from Memories → Recordings / the recordings table)
//
// Needs: SUPABASE_URL + SUPABASE_SERVICE_ROLE_KEY (to read the transcripts), and the key of each provider used
// (ANTHROPIC_API_KEY, OPENROUTER_API_KEY, GEMINI_API_KEY). Writes docs/eval/meeting-summary-<date>.md — a table
// per meeting (one column per model: time, $, and the notes) for Law to judge. The bar: numbers, owners and
// dates exactly right; nothing invented. Then put the winner first in model_routes (see the llm_router migration).
// The transcripts stay on this PC (docs/eval is git-ignored — they're private meetings).
import { createClient } from "npm:@supabase/supabase-js@2";
import { RouteEntry } from "../supabase/functions/_shared/llm.ts";
import { Line, memoryText, summarise, transcriptText } from "../supabase/functions/_shared/meetingSummary.ts";

// Load supabase/functions/.env into the environment (values already set win).
try {
  for (const raw of (await Deno.readTextFile("supabase/functions/.env")).split("\n")) {
    const m = raw.match(/^([A-Z0-9_]+)=(.*)$/);
    if (m && !Deno.env.get(m[1])) Deno.env.set(m[1], m[2].trim().replace(/^"|"$/g, ""));
  }
} catch { /* no .env — use the environment */ }

const args = [...Deno.args];
const mi = args.indexOf("--models");
const models = (mi >= 0 ? args.splice(mi, 2)[1] : "anthropic:default,gemini:gemini-2.5-flash").split(",").map((s) => {
  const [provider, ...rest] = s.trim().split(":");
  return { provider, model: rest.join(":") || "default", max_tokens: 4000 } as RouteEntry;
});
const ids = args.filter((a) => a.startsWith("rec-"));
if (!ids.length) { console.error("give one or more recording ids (rec-…)"); Deno.exit(1); }

const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, { auth: { persistSession: false } });

let out = `# Meeting summary eval — ${new Date().toISOString().slice(0, 16)}\n\nModels: ${models.map((m) => `${m.provider}/${m.model}`).join(", ")}\n`;
for (const id of ids) {
  const { data: rec } = await db.from("recordings").select("id,title,started_at,duration_s,bookmarks").eq("client_id", id).maybeSingle();
  if (!rec) { console.error(`${id}: not found`); continue; }
  const { data: chunks } = await db.from("recording_chunks").select("seq,duration_s").eq("recording_id", rec.id).order("seq");
  const off = new Map<number, number>(); let acc = 0;
  for (const c of chunks ?? []) { off.set(c.seq, acc); acc += c.duration_s ?? 0; }
  const { data: segs } = await db.from("recording_segments").select("seq,t_start_s,speaker,text").eq("recording_id", rec.id).order("seq").order("idx").limit(20000);
  const lines: Line[] = (segs ?? []).map((s) => ({ t: (off.get(s.seq) ?? 0) + Number(s.t_start_s), speaker: s.speaker, text: s.text }));
  out += `\n## ${rec.title || id} — ${Math.round((rec.duration_s ?? 0) / 60)} min, ${lines.length} lines\n\n<details><summary>Transcript</summary>\n\n\`\`\`\n${transcriptText(lines, rec.bookmarks ?? []).slice(0, 60000)}\n\`\`\`\n</details>\n`;
  for (const m of models) {
    const t0 = Date.now();
    try {
      // The live prompt and schema, forced onto this one model.
      const s = await summarise(lines, { title: rec.title, date: rec.started_at.slice(0, 10), durationS: rec.duration_s, bookmarks: rec.bookmarks ?? [], routes: [m] });
      out += `\n### ${m.provider}/${m.model} — ${((Date.now() - t0) / 1000).toFixed(1)} s\n\n${memoryText(s, rec.started_at.slice(0, 10))}\n`;
      console.log(`${id} ${m.provider}/${m.model}: ok ${Date.now() - t0} ms`);
    } catch (e) {
      out += `\n### ${m.provider}/${m.model} — FAILED\n\n${e instanceof Error ? e.message : e}\n`;
      console.log(`${id} ${m.provider}/${m.model}: FAILED ${e}`);
    }
  }
}

await Deno.mkdir("docs/eval", { recursive: true });
const file = `docs/eval/meeting-summary-${new Date().toISOString().slice(0, 10)}.md`;
await Deno.writeTextFile(file, out);
console.log(`\nWrote ${file}`);
