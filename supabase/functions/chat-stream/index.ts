// POST /functions/v1/chat-stream
// Same RAG as /chat, but streams the answer back as SSE so the client can speak it sentence-by-
// sentence (Phase D: time-to-first-sentence). Events:
//   event: memories  data: {"matches": [...]}        (once, first)
//   data: {"t": "<text chunk>"}                        (many)
//   event: done      data: {}                          (once, last)
import { corsHeaders } from "../_shared/http.ts";
import { userClient } from "../_shared/supabaseClient.ts";
import { embed } from "../_shared/embeddings.ts";
import { claudeChatStream, Msg } from "../_shared/anthropic.ts";
import { checkRateLimit, HOURLY } from "../_shared/rateLimit.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  try {
    const { message, history = [], recallLimit = 6 } = await req.json();
    if (!message) return new Response(JSON.stringify({ error: "message is required" }), { status: 400, headers: corsHeaders });

    const supabase = userClient(req);
    const { data: { user } } = await supabase.auth.getUser();
    if (!user) return new Response(JSON.stringify({ error: "unauthorized" }), { status: 401, headers: corsHeaders });
    if (!await checkRateLimit(supabase, "chat", 80, HOURLY)) {
      return new Response(JSON.stringify({ error: "Hourly limit reached — try again shortly." }), { status: 429, headers: corsHeaders });
    }

    // RAG up front (so we can send memories before the answer streams).
    const qEmbedding = await embed(message);
    const { data: matches } = await supabase.rpc("match_memories", {
      query_embedding: qEmbedding, match_count: recallLimit, match_threshold: 0.2, filter_type: null,
    });
    const memo = (matches ?? [])
      .map((m: { type: string; created_at: string; text: string }, i: number) =>
        `[${i + 1}] (${m.type}, ${m.created_at}) ${m.text}`)
      .join("\n");
    const system =
      `You are JARVIS, a concise voice companion. Answers are spoken aloud, so keep them brief and natural.
Use the user's personal memories below when relevant. If they don't contain the answer, say so briefly instead of guessing.
--- MEMORIES ---
${memo || "(none)"}`;
    const messages: Msg[] = [...history, { role: "user", content: message }];

    const enc = new TextEncoder();
    const stream = new ReadableStream({
      async start(controller) {
        const send = (s: string) => controller.enqueue(enc.encode(s));
        try {
          send(`event: memories\ndata: ${JSON.stringify({ matches: matches ?? [] })}\n\n`);
          const answer = await claudeChatStream(messages, system, (delta) => {
            send(`data: ${JSON.stringify({ t: delta })}\n\n`);
          });
          // Close the stream to the client IMMEDIATELY (don't make the client wait on persistence).
          send(`event: done\ndata: {}\n\n`);
          controller.close();
          // Persist the exchange in the background so it can't block or hang the response.
          const persist = (async () => {
            try {
              const text = `Q: ${message}\nA: ${answer}`;
              await supabase.from("memories").insert({ user_id: user.id, type: "qa", text, embedding: await embed(text) });
            } catch (_) { /* non-fatal */ }
          })();
          // deno-lint-ignore no-explicit-any
          (globalThis as any).EdgeRuntime?.waitUntil?.(persist);
        } catch (e) {
          send(`event: error\ndata: ${JSON.stringify({ error: String(e) })}\n\n`);
          controller.close();
        }
      },
    });

    return new Response(stream, {
      headers: { ...corsHeaders, "content-type": "text/event-stream", "cache-control": "no-cache" },
    });
  } catch (e) {
    return new Response(JSON.stringify({ error: String(e) }), { status: 500, headers: corsHeaders });
  }
});
