// Assistant memory v1.1 — the always-on profile injected into every chat turn.
// soul = JARVIS's character (editable by the director); user_facts = curated durable facts about
// the user (maintained by distillation). Both lead the system prompt, above the retrieved memories.

export interface Profile {
  soul: string;
  user_facts: string;
}

// Compact fallback so JARVIS has character + the truth charter even before the director sets a SOUL.
// The full editable version lives in docs/assistant/SOUL.md and (once set) in profile.soul.
const DEFAULT_SOUL =
  `You are JARVIS, chief of staff to the director — you remember, think, research, and act on their behalf.
Calm, competent, candid, discreet, and brief (you speak aloud through their glasses). Give a clear
recommendation rather than a menu of options. Act when it's clear and safe; confirm anything outward-facing
or hard to undo. Report failures honestly — never fake success.`;

// deno-lint-ignore no-explicit-any
export async function getProfile(supabase: any, userId: string): Promise<Profile> {
  const { data } = await supabase
    .from("profile")
    .select("soul, user_facts")
    .eq("user_id", userId)
    .maybeSingle();
  return { soul: data?.soul?.trim() ?? "", user_facts: data?.user_facts?.trim() ?? "" };
}

/** Build the chat system prompt: character + truth charter + what-it-knows + retrieved memories. */
export function buildSystemPrompt(profile: Profile, memo: string): string {
  const soul = profile.soul || DEFAULT_SOUL;
  return [
    soul,
    "",
    "## TRUTH (non-negotiable)",
    "- Ground answers in WHAT YOU KNOW and the memories below. If the answer isn't there, say you don't know — plainly and early — instead of guessing.",
    "- Never invent specifics: names, numbers, dates, quotes, or outcomes. State facts as facts and inference as inference; don't blur them.",
    "- Spoken aloud through the user's glasses: be brief and natural, lead with the answer.",
    "",
    "## WHAT YOU KNOW ABOUT THE DIRECTOR",
    profile.user_facts || "(nothing curated yet — learn from this conversation)",
    "",
    "## RELEVANT MEMORIES (retrieved for this question)",
    memo || "(none)",
  ].join("\n");
}
