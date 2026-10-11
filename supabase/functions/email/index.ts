// POST /functions/v1/email — Gmail DRAFTS in the cloud (Phase 1, replaces the PC bridge).
//   { op: "draft", text, to? }  → { spoken, text, to, toName, subject, body, draftId, needsAddress, alternatives[] }
// JARVIS never sends email: it only has the gmail.compose permission and only ever creates a
// draft, which Law reviews and sends from Gmail. The recipient comes from what was said, or is
// looked up in Law's Google contacts (People API: saved contacts + "other contacts" he's emailed).
// `text` = spoken + "\nDRAFT:\nTo: …\nSubject: …\n\n<body>" (the app's email card format).
import { corsHeaders, json } from "../_shared/http.ts";
import { userClient } from "../_shared/supabaseClient.ts";
import { checkRateLimit, HOURLY } from "../_shared/rateLimit.ts";
import { claudeJson } from "../_shared/anthropic.ts";
import { getProfile } from "../_shared/profile.ts";
import { googleApi, GoogleNotConnected } from "../_shared/google.ts";

// Anything written in Law's name gets the main model (PRODUCT_PLAN: "Email drafts → Claude").
const EMAIL_MODEL = Deno.env.get("EMAIL_MODEL") ?? Deno.env.get("CLAUDE_MODEL") ?? "claude-sonnet-4-6";
const EMAIL_RE = /[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/i;

const DRAFT_SCHEMA = {
  type: "object",
  properties: {
    recipientName: { type: "string", description: "who it's to, as said (e.g. 'Mr Ade'); '' if nobody" },
    recipientEmail: { type: "string", description: "only if an email address was actually said/typed; else ''" },
    subject: { type: "string" },
    body: { type: "string", description: "the email body, plain text, ending with the sign-off" },
  },
  required: ["recipientName", "recipientEmail", "subject", "body"],
  additionalProperties: false,
};

interface Draft {
  recipientName: string;
  recipientEmail: string;
  subject: string;
  body: string;
}

interface Person { name: string; email: string }

/** Look a name up in saved contacts, then in "other contacts" (people Law has emailed). */
async function findContacts(userId: string, name: string): Promise<Person[]> {
  const out: Person[] = [];
  const add = (results: unknown) => {
    for (const r of (results as { person?: { names?: { displayName?: string }[]; emailAddresses?: { value?: string }[] } }[]) ?? []) {
      const p = r.person;
      const email = p?.emailAddresses?.[0]?.value;
      if (email && !out.some((o) => o.email.toLowerCase() === email.toLowerCase())) {
        out.push({ name: p?.names?.[0]?.displayName ?? email, email });
      }
    }
  };
  const mask = "names,emailAddresses";
  const q = encodeURIComponent(name);
  // The People API wants an empty "warm-up" search before the first real one (it builds its cache).
  try {
    await googleApi(userId, `https://people.googleapis.com/v1/people:searchContacts?query=&readMask=${mask}`);
    add((await googleApi(userId, `https://people.googleapis.com/v1/people:searchContacts?query=${q}&readMask=${mask}&pageSize=5`)).results);
  } catch (e) { if (e instanceof GoogleNotConnected) throw e; console.warn("searchContacts:", e); }
  try {
    await googleApi(userId, `https://people.googleapis.com/v1/otherContacts:search?query=&readMask=emailAddresses,names`);
    add((await googleApi(userId, `https://people.googleapis.com/v1/otherContacts:search?query=${q}&readMask=emailAddresses,names&pageSize=5`)).results);
  } catch (e) { if (e instanceof GoogleNotConnected) throw e; console.warn("otherContacts:", e); }
  return out;
}

/** RFC 2822 message, base64url — what Gmail's drafts.create wants. UTF-8 safe subject. */
function rawMessage(to: string, subject: string, body: string): string {
  const b64 = (s: string) => btoa(String.fromCharCode(...new TextEncoder().encode(s)));
  const msg = [
    ...(to ? [`To: ${to}`] : []),
    `Subject: =?UTF-8?B?${b64(subject)}?=`,
    "MIME-Version: 1.0",
    "Content-Type: text/plain; charset=UTF-8",
    "Content-Transfer-Encoding: base64",
    "",
    b64(body).replace(/.{76}/g, "$&\r\n"),
  ].join("\r\n");
  return b64(msg).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function draft(supabase: ReturnType<typeof userClient>, userId: string, text: string, toOverride: string) {
  const profile = await getProfile(supabase, userId);
  const d = await claudeJson<Draft>(
    `You draft emails for Law (the user) to send from his own Gmail — he reviews every draft before sending.
Write in his voice: warm but businesslike, direct, short paragraphs, plain text (no markdown), British/Nigerian
English spelling. Keep it as short as the request allows. Never invent facts, figures, dates or promises that
weren't in the request — if something is needed but missing, write [brackets] for Law to fill in.
Sign off with "Best regards," and his first name. The request may be spoken (transcribed), so ignore filler.
What JARVIS knows about Law:
${profile.user_facts || "(nothing yet)"}`,
    text,
    DRAFT_SCHEMA,
    { model: EMAIL_MODEL, maxTokens: 4000, effort: "medium" },
  );

  let to = toOverride || d.recipientEmail || (text.match(EMAIL_RE)?.[0] ?? "");
  let toName = d.recipientName;
  let alternatives: Person[] = [];
  if (!to && d.recipientName) {
    const found = await findContacts(userId, d.recipientName);
    if (found.length) {
      to = found[0].email;
      toName = found[0].name;
      alternatives = found.slice(1, 4);
    }
  }

  const created = await googleApi(userId, "https://gmail.googleapis.com/gmail/v1/users/me/drafts", {
    method: "POST",
    body: JSON.stringify({ message: { raw: rawMessage(to, d.subject, d.body) } }),
  });

  const who = toName || to;
  let spoken: string;
  if (to) {
    spoken = `Draft to ${who} about ${d.subject.toLowerCase()} is in your Gmail drafts.`;
    if (alternatives.length) spoken += ` I used ${to} — check it's the right ${toName.split(" ")[0]}.`;
  } else if (d.recipientName) {
    spoken = `I drafted it, but I couldn't find ${d.recipientName} in your contacts, so the To line is empty. Add the address in Gmail.`;
  } else {
    spoken = "Draft saved in your Gmail drafts. Add who it's to before you send it.";
  }
  const card = `DRAFT:\nTo: ${to ? `${toName && toName !== to ? toName + " <" + to + ">" : to}` : "(add in Gmail)"}\nSubject: ${d.subject}\n\n${d.body}`;
  return {
    spoken,
    text: `${spoken}\n${card}`,
    to,
    toName,
    subject: d.subject,
    body: d.body,
    draftId: created.id ?? "",
    needsAddress: !to,
    alternatives,
  };
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  const t0 = Date.now();
  try {
    const supabase = userClient(req);
    const { data: { user } } = await supabase.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);
    if (!await checkRateLimit(supabase, "email", 30, HOURLY)) {
      return json({ error: "Hourly email limit reached — try again shortly." }, 429);
    }
    const { op, text, to } = await req.json();
    if (op !== "draft") return json({ error: "op must be draft" }, 400);
    const q = String(text ?? "").trim();
    if (!q) return json({ error: "text is required" }, 400);
    const toOverride = typeof to === "string" && EMAIL_RE.test(to) ? to.trim() : "";
    return json({ ...(await draft(supabase, user.id, q, toOverride)), ms: Date.now() - t0 });
  } catch (e) {
    if (e instanceof GoogleNotConnected) return json({ error: e.message, notConnected: true, spoken: e.message }, 409);
    console.error("email error:", e);
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
