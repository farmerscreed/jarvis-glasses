// POST /functions/v1/research { query, history?, image? }
// image = base64 JPEG of what the wearer is looking at (camera glasses). Claude identifies the object
// from the photo (brand, model, labels) and then researches THAT — one call, vision + web_search.
// The deliberate lane's research skill, hosted in the cloud (replaces the PC Agent Bridge for
// research — works anywhere, no tether). Claude + Anthropic's server-side web_search tool: it
// searches, reads, and answers in a SPOKEN style; we build the "Sources:" list from the citations
// Claude actually used (more reliable than asking the model to list URLs).
// Returns { text, spoken, sources: [{title,url}], searches, ms }.
//   text   = spoken + "\nSources:\n<url per line>"  (same shape the app's research cards parse)
import { corsHeaders, json } from "../_shared/http.ts";
import { userClient } from "../_shared/supabaseClient.ts";
import { checkRateLimit, HOURLY } from "../_shared/rateLimit.ts";

const ANTHROPIC_API_KEY = Deno.env.get("ANTHROPIC_API_KEY");
const MODEL = Deno.env.get("RESEARCH_MODEL") ?? Deno.env.get("CLAUDE_MODEL") ?? "claude-sonnet-4-6";

const SYSTEM = (today: string) => `You are JARVIS's research agent, acting for the user (Law). Today is ${today}.
Be truthful above all. ALWAYS use the web_search tool to verify anything time-sensitive or factual
(prices, availability, news, versions, schedules, rules, who holds a role) — never rely on memory for
things that could have changed. If sources conflict or you can't verify something, say so plainly.
When the question is about prices or availability and no location is given, assume Nigeria
(Port Harcourt / Lagos) and quote naira where sources allow, with USD if useful.
Answer in a natural SPOKEN style for an earpiece: 3 to 6 short sentences, lead with the answer,
no markdown, no bullet points, no URLs, no "Sources" line (the app adds sources itself).`;

const VISUAL = `
The user is wearing camera glasses. The attached photo is exactly what they are looking at right now,
and words like "this", "that", "it" or "in front of me" refer to the main object in it.
First identify that object as precisely as you can: read any brand, logo, model number, label or
packaging text, and note the type, material, colour and size. Then research THAT item with web_search
(price, where to buy, reviews, specs, safety — whatever they asked; if they only said "research this",
cover what it is, typical price in Nigeria and where to buy it, and one notable pro or con).
If you can't tell the exact brand or model, say so briefly and research the closest match by its type
and features (e.g. "a white wooden baby cot with drop-side rails").
Before the spoken answer, output ONE line exactly like: ITEM: <short name of the object, max 8 words>
then a newline, then the spoken answer. Never say you cannot see images — you can see the photo.`;

type Block = {
  type: string;
  text?: string;
  citations?: { type: string; url?: string; title?: string }[];
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  const t0 = Date.now();
  try {
    const supabase = userClient(req);
    const { data: { user } } = await supabase.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);
    if (!await checkRateLimit(supabase, "research", 40, HOURLY)) {
      return json({ error: "Hourly research limit reached — try again shortly." }, 429);
    }
    if (!ANTHROPIC_API_KEY) return json({ error: "ANTHROPIC_API_KEY not set" }, 500);

    const { query, history, image } = await req.json();
    const q = String(query ?? "").trim();
    if (!q) return json({ error: "query is required" }, 400);
    const img = typeof image === "string" && image.length > 100 ? image : "";

    const messages: unknown[] = [];
    if (Array.isArray(history)) {
      for (const m of history.slice(-6)) {
        if ((m?.role === "user" || m?.role === "assistant") && typeof m?.content === "string") {
          messages.push({ role: m.role, content: m.content });
        }
      }
    }
    messages.push(img
      ? {
        role: "user",
        content: [
          { type: "image", source: { type: "base64", media_type: "image/jpeg", data: img } },
          { type: "text", text: q },
        ],
      }
      : { role: "user", content: q });

    const today = new Date().toISOString().slice(0, 10);
    let content: Block[] = [];
    let searches = 0;
    // pause_turn: a long server-tool turn can pause; send it back unchanged to continue (max 3).
    for (let i = 0; i < 3; i++) {
      const res = await fetch("https://api.anthropic.com/v1/messages", {
        method: "POST",
        headers: {
          "x-api-key": ANTHROPIC_API_KEY,
          "anthropic-version": "2023-06-01",
          "content-type": "application/json",
        },
        body: JSON.stringify({
          model: MODEL,
          max_tokens: 1500,
          system: SYSTEM(today) + (img ? VISUAL : ""),
          messages,
          tools: [{ type: "web_search_20250305", name: "web_search", max_uses: 5 }],
        }),
      });
      if (!res.ok) return json({ error: `anthropic ${res.status}: ${await res.text()}` }, 502);
      const data = await res.json();
      searches += data.usage?.server_tool_use?.web_search_requests ?? 0;
      content = content.concat(data.content ?? []);
      if (data.stop_reason !== "pause_turn") break;
      messages.push({ role: "assistant", content: data.content });
    }

    // The final answer is the text after the last tool result; earlier text blocks are usually
    // "I'll search for…" narration. Take text blocks that follow the last web_search_tool_result.
    let lastTool = -1;
    content.forEach((b, i) => { if (b.type === "web_search_tool_result") lastTool = i; });
    const answerBlocks = content.slice(lastTool + 1).filter((b) => b.type === "text");
    // "ITEM: …" (visual research) may sit before the searches — find it anywhere, then strip it.
    let item = "";
    for (const b of content) {
      const m = b.type === "text" ? (b.text ?? "").match(/ITEM:\s*([^\n]+)/) : null;
      if (m) item = m[1].trim();
    }
    const spoken = (answerBlocks.length ? answerBlocks : content.filter((b) => b.type === "text"))
      .map((b) => b.text ?? "").join("").replace(/ITEM:[^\n]*\n?/g, "")
      .replace(/\*\*|__|`/g, "").replace(/\s+\n/g, "\n").trim();

    const seen = new Set<string>();
    const sources: { title: string; url: string }[] = [];
    for (const b of content) {
      for (const c of b.citations ?? []) {
        if (c.url && !seen.has(c.url)) { seen.add(c.url); sources.push({ title: c.title ?? c.url, url: c.url }); }
      }
    }
    const top = sources.slice(0, 6);
    const text = top.length ? `${spoken}\nSources:\n${top.map((s) => s.url).join("\n")}` : spoken;
    return json({ text, spoken, item, sources: top, searches, visual: !!img, ms: Date.now() - t0 });
  } catch (e) {
    return json({ error: String(e) }, 500);
  }
});
