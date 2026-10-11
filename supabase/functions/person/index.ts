// POST /functions/v1/person { text } → { name, role, organisation, where, notes, phone, email, memoryText, spoken }
// People memory (Phase 1): turns "Jarvis, remember this person: Mr Ade, supplier, met at the
// Trans-Amadi yard" into clean fields. Saving is done by the app (offline-first memory store, type
// 'person'), so this only structures — it never invents details that weren't said.
import { corsHeaders, json } from "../_shared/http.ts";
import { userClient } from "../_shared/supabaseClient.ts";
import { checkRateLimit, HOURLY } from "../_shared/rateLimit.ts";
import { claudeJson, FAST_MODEL } from "../_shared/anthropic.ts";

const TZ = Deno.env.get("DEFAULT_TZ") ?? "Africa/Lagos";

const SCHEMA = {
  type: "object",
  properties: {
    name: { type: "string", description: "how the user refers to them, e.g. 'Mr Ade'; '' if no name was said" },
    role: { type: "string", description: "what they do / who they are to the user (supplier, site engineer…)" },
    organisation: { type: "string" },
    where: { type: "string", description: "where they met / where they're based, if said" },
    notes: { type: "string", description: "anything else worth remembering, short" },
    phone: { type: "string" },
    email: { type: "string" },
  },
  required: ["name", "role", "organisation", "where", "notes", "phone", "email"],
  additionalProperties: false,
};

interface Person {
  name: string;
  role: string;
  organisation: string;
  where: string;
  notes: string;
  phone: string;
  email: string;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  try {
    const supabase = userClient(req);
    const { data: { user } } = await supabase.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);
    if (!await checkRateLimit(supabase, "person", 120, HOURLY)) {
      return json({ error: "Hourly limit reached — try again shortly." }, 429);
    }
    const { text } = await req.json();
    const said = String(text ?? "").trim();
    if (!said) return json({ error: "text is required" }, 400);

    const p = await claudeJson<Person>(
      `The user (wearing camera glasses) wants JARVIS to remember a person they met. Pull out ONLY what was
said: name, role, organisation, where, notes, phone, email. Use "" for anything not said — never guess.
Keep titles as said (Mr, Mrs, Chief, Engr, Dr, Alhaji…). The text is transcribed speech; fix obvious
transcription slips in common words but not in names.`,
      said,
      SCHEMA,
      { model: FAST_MODEL, maxTokens: 2000, effort: "low" },
    );

    const day = new Intl.DateTimeFormat("en-GB", { timeZone: TZ, weekday: "short", day: "numeric", month: "short", year: "numeric" })
      .format(new Date());
    const who = p.name || "Someone (no name given)";
    const parts = [
      p.role && p.organisation ? `${p.role}, ${p.organisation}` : p.role || p.organisation,
      p.where ? `met at ${p.where}` : "",
      `on ${day}`,
      p.notes,
      p.phone ? `phone ${p.phone}` : "",
      p.email ? `email ${p.email}` : "",
    ].filter(Boolean);
    // The searchable memory text — written so "who was the supplier I met last week?" finds it.
    const memoryText = `Person: ${who} — ${parts.join(" · ")}`;
    // Read back what was saved — including a number, so a misheard digit is caught on the spot.
    const extra = [p.phone ? `number ${p.phone.split("").join(" ")}` : "", p.email ? `email ${p.email}` : ""]
      .filter(Boolean).join(", ");
    const spoken = p.name
      ? `Got it: ${p.name}${p.role ? ", " + p.role : ""}${p.where ? ", met at " + p.where : ""}${extra ? ", " + extra : ""}. Saved.`
      : "Saved. I didn't catch a name, so add one next time if you can.";
    return json({ ...p, memoryText, spoken });
  } catch (e) {
    console.error("person error:", e);
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
