// POST /functions/v1/chat
// Body: { message, history?: [{role, content}], recallLimit? }
// RAG: embed the message -> recall relevant memories -> Claude answers grounded in them ->
// persist the Q&A back into the memory index. Returns { answer, memories_used }.
import { corsHeaders, json } from "../_shared/http.ts";
import { userClient } from "../_shared/supabaseClient.ts";
import { embed } from "../_shared/embeddings.ts";
import { claudeChat, Msg } from "../_shared/anthropic.ts";
import { checkRateLimit, HOURLY } from "../_shared/rateLimit.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  try {
    const { message, history = [], recallLimit = 6 } = await req.json();
    if (!message) return json({ error: "message is required" }, 400);

    const supabase = userClient(req);
    const { data: { user } } = await supabase.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);
    if (!await checkRateLimit(supabase, "chat", 80, HOURLY)) {
      return json({ error: "Hourly limit reached — try again shortly." }, 429);
    }

    // 1. Retrieve relevant memories.
    const qEmbedding = await embed(message);
    const { data: matches } = await supabase.rpc("match_memories", {
      query_embedding: qEmbedding,
      match_count: recallLimit,
      match_threshold: 0.2,
      filter_type: null,
    });

    // 2. Ground Claude in the user's memories.
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
    const answer = await claudeChat(messages, system);

    // 3. Write the exchange back into the memory index (best-effort).
    try {
      const text = `Q: ${message}\nA: ${answer}`;
      const aEmbedding = await embed(text);
      await supabase.from("memories").insert({
        user_id: user.id,
        type: "qa",
        text,
        embedding: aEmbedding,
      });
    } catch (_) {
      // non-fatal: the answer still returns even if persistence fails
    }

    return json({ answer, memories_used: matches ?? [] });
  } catch (e) {
    return json({ error: String(e) }, 500);
  }
});
