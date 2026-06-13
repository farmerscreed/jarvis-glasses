// POST /functions/v1/distill { transcript: [{role, content}, ...] | string }
// Distillation (assistant memory v1.2): extract durable facts/preferences/corrections about the user
// from a finished conversation and MERGE them into profile.user_facts — so JARVIS keeps a clean,
// curated profile instead of hoarding every Q&A. Returns the updated user_facts.
import { corsHeaders, json } from "../_shared/http.ts";
import { userClient } from "../_shared/supabaseClient.ts";
import { claudeChat } from "../_shared/anthropic.ts";
import { getProfile } from "../_shared/profile.ts";
import { checkRateLimit, HOURLY } from "../_shared/rateLimit.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  try {
    const supabase = userClient(req);
    const { data: { user } } = await supabase.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);
    if (!await checkRateLimit(supabase, "distill", 60, HOURLY)) {
      return json({ error: "Hourly limit reached — try again shortly." }, 429);
    }

    const { transcript } = await req.json();
    const convo = Array.isArray(transcript)
      ? transcript.map((t: { role: string; content: string }) => `${t.role}: ${t.content}`).join("\n")
      : String(transcript ?? "");
    if (!convo.trim()) return json({ error: "transcript is required" }, 400);

    const current = (await getProfile(supabase, user.id)).user_facts;

    const system =
      `You maintain a compact, durable profile of facts about a user, for their personal assistant.
Given the CURRENT FACTS and a CONVERSATION, return the UPDATED FACTS.
Rules:
- Keep only durable, useful things: identity, stable preferences, important people/places/projects,
  commitments, and corrections the user made. Skip one-off chatter, transient state, and anything
  trivially rediscoverable.
- MERGE with the current facts: keep what's still true, apply corrections, remove only what's now
  wrong or obsolete. Do not invent anything not supported by the conversation.
- Keep it tight (under ~1500 characters), plain "- " bullets, grouped sensibly.
- If the conversation adds nothing durable, return the CURRENT FACTS unchanged.
- Output ONLY the bullet list — no preamble, no commentary.`;
    const userMsg = `CURRENT FACTS:\n${current || "(none)"}\n\nCONVERSATION:\n${convo}`;

    const updated = (await claudeChat([{ role: "user", content: userMsg }], system)).trim();
    if (updated) {
      await supabase.from("profile").upsert(
        { user_id: user.id, user_facts: updated, updated_at: new Date().toISOString() },
        { onConflict: "user_id" },
      );
    }
    return json({ user_facts: updated || current });
  } catch (e) {
    return json({ error: String(e) }, 500);
  }
});
