// POST /functions/v1/translate { op, ... }
// Translation backend. Live speech translation itself runs phone ⇄ Google directly (lowest latency);
// this function only does the parts that need a server:
//   op "token"  { target }            → a short-lived, single-use Gemini Live Translate token, locked
//                                        to the translate model + target language (the Gemini key
//                                        never leaves the server). Returns { token, model, urls[] }.
//   op "phrase" { text, target? }      → "how do I say X in Y": { translation, romanization, langCode,
//                                        langName, spoken } — spoken = short English lead-in.
//   op "save"   { lines[], mode, seconds } → summarize a finished translated conversation and store it
//                                        as a memory (tags: translation, <lang>). Returns { summary }.
import { corsHeaders, json } from "../_shared/http.ts";
import { userClient } from "../_shared/supabaseClient.ts";
import { checkRateLimit, HOURLY } from "../_shared/rateLimit.ts";
import { embed } from "../_shared/embeddings.ts";

const ANTHROPIC_API_KEY = Deno.env.get("ANTHROPIC_API_KEY");
const CLAUDE_MODEL = Deno.env.get("CLAUDE_MODEL") ?? "claude-sonnet-4-6";

async function claudeChat(messages: { role: string; content: string }[], system: string, maxTokens: number): Promise<string> {
  if (!ANTHROPIC_API_KEY) throw new Error("ANTHROPIC_API_KEY is not set");
  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: { "x-api-key": ANTHROPIC_API_KEY, "anthropic-version": "2023-06-01", "content-type": "application/json" },
    body: JSON.stringify({ model: CLAUDE_MODEL, max_tokens: maxTokens, system, messages }),
  });
  if (!res.ok) throw new Error(`anthropic ${res.status}: ${await res.text()}`);
  const data = await res.json();
  return (data.content ?? []).map((b: { text?: string }) => b.text ?? "").join("");
}

const GEMINI_API_KEY = Deno.env.get("GEMINI_API_KEY");
const LIVE_MODEL = Deno.env.get("LIVE_TRANSLATE_MODEL") ?? "gemini-3.5-live-translate-preview";
// Token sessions must use the *Constrained* endpoint (verified: v1beta + v1alpha both work; the plain
// endpoint rejects tokens). Served from here so a URL can change without an app update.
const WS_URLS = [
  "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained?access_token=",
  "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained?access_token=",
];

function extractJson(s: string): Record<string, unknown> | null {
  const m = s.match(/\{[\s\S]*\}/);
  if (!m) return null;
  try { return JSON.parse(m[0]); } catch { return null; }
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  try {
    const supabase = userClient(req);
    const { data: { user } } = await supabase.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);
    const body = await req.json();
    const op = String(body.op ?? "");

    if (op === "token") {
      if (!await checkRateLimit(supabase, "translate_token", 120, HOURLY)) {
        return json({ error: "Hourly translation limit reached." }, 429);
      }
      if (!GEMINI_API_KEY) return json({ error: "GEMINI_API_KEY not set" }, 500);
      const target = String(body.target ?? "en").trim() || "en";
      const now = Date.now();
      const res = await fetch("https://generativelanguage.googleapis.com/v1beta/auth_tokens", {
        method: "POST",
        headers: { "x-goog-api-key": GEMINI_API_KEY, "Content-Type": "application/json" },
        body: JSON.stringify({
          uses: 1,
          expireTime: new Date(now + 30 * 60_000).toISOString(),
          newSessionExpireTime: new Date(now + 2 * 60_000).toISOString(),
          // VERIFIED 2026-10-09 against the live API (the public docs' JSON is wrong): REST field is
          // bidiGenerateContentSetup; the transcription configs are TOP-LEVEL of the setup, and
          // translationConfig lives inside generationConfig. The whole config is locked server-side,
          // so the app only needs to send { setup: { model } }.
          bidiGenerateContentSetup: {
            model: `models/${LIVE_MODEL}`,
            generationConfig: {
              responseModalities: ["AUDIO"],
              // echo=false: stay silent when the speaker already speaks the target language — this is
              // what lets one mic stream feed both directions of a conversation.
              translationConfig: { targetLanguageCode: target, echoTargetLanguage: false },
            },
            inputAudioTranscription: {},
            outputAudioTranscription: {},
          },
        }),
      });
      const txt = await res.text();
      if (!res.ok) return json({ error: `gemini token ${res.status}: ${txt}` }, 502);
      const tok = JSON.parse(txt);
      return json({ token: tok.name, model: `models/${LIVE_MODEL}`, target, urls: WS_URLS });
    }

    if (op === "phrase") {
      if (!await checkRateLimit(supabase, "translate_text", 200, HOURLY)) {
        return json({ error: "Hourly translation limit reached." }, 429);
      }
      const text = String(body.text ?? "").trim();
      if (!text) return json({ error: "text is required" }, 400);
      const target = body.target ? String(body.target) : "";
      const out = await claudeChat(
        [{ role: "user", content: text }],
        `You are JARVIS's interpreter. The user wants to say something in another language, e.g.
"how do I say 'what is your best price' in Mandarin". ${target ? `Target language code: ${target}.` : "Infer the target language from the request."}
Translate naturally and politely, the way a local would say it in person (use Simplified Chinese for Mandarin
unless asked otherwise). Reply with ONLY a JSON object:
{"langCode":"BCP-47 code e.g. zh-Hans, fr, ar","langName":"e.g. Mandarin","phrase":"the English phrase being translated",
"translation":"the translation in native script","romanization":"pinyin with tone marks / Latin transliteration, or empty for Latin-script languages"}`,
        400,
      );
      const j = extractJson(out);
      if (!j || !j.translation) return json({ error: "couldn't translate that" }, 502);
      const spoken = `In ${j.langName ?? "that language"}, you'd say:`;
      return json({ ...j, spoken });
    }

    if (op === "save") {
      if (!await checkRateLimit(supabase, "translate_text", 200, HOURLY)) {
        return json({ error: "Hourly translation limit reached." }, 429);
      }
      const lines = Array.isArray(body.lines) ? body.lines.slice(0, 400) : [];
      if (!lines.length) return json({ summary: "" });
      const mode = String(body.mode ?? "conversation");
      const minutes = Math.max(1, Math.round(Number(body.seconds ?? 0) / 60));
      const langs = [...new Set(lines.map((l: { lang?: string }) => l.lang).filter(Boolean))] as string[];
      const transcript = lines.map((l: { who?: string; lang?: string; text?: string; translation?: string }) =>
        `[${l.who ?? "?"}${l.lang ? ` · ${l.lang}` : ""}] ${l.text ?? ""}${l.translation ? `  →  ${l.translation}` : ""}`
      ).join("\n").slice(0, 30_000);
      const summary = await claudeChat(
        [{ role: "user", content: transcript }],
        `You are JARVIS. Below is a transcript of a translated ${mode} the user (Law) just had (~${minutes} min).
Lines marked "me" are Law; "them" is the other person; each line may include its translation.
Write a short English memory note: who/what it was about, the key points, any numbers/prices/dates/
commitments made by either side, and open follow-ups. 3-8 short lines, plain text, no markdown headers.
Be factual — only what the transcript supports.`,
        600,
      );
      const date = new Date().toISOString().slice(0, 16).replace("T", " ");
      const text = `Translated ${mode} (${langs.join(", ") || "unknown language"}) — ${date} UTC, ~${minutes} min\n${summary.trim()}\n\nTranscript:\n${transcript}`;
      const { error } = await supabase.from("memories").insert({
        user_id: user.id,
        type: "note",
        text,
        embedding: await embed(text.slice(0, 8000)),
        tags: ["translation", ...langs],
        metadata: { mode, seconds: body.seconds ?? null },
      });
      if (error) return json({ error: error.message, summary }, 500);
      return json({ summary: summary.trim() });
    }

    return json({ error: `unknown op: ${op}` }, 400);
  } catch (e) {
    return json({ error: String(e) }, 500);
  }
});
