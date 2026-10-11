// POST /functions/v1/calendar — Law's Google Calendar, in the cloud (Phase 1, replaces the PC bridge).
//   { op: "query",   text }  → { spoken, text, events[] }          read only, no confirm
//   { op: "propose", text }  → { id, spoken, text, event }          nothing written yet
//   { op: "confirm", id }    → { spoken, text, link }               creates the proposed event
//   { op: "cancel",  id }    → { spoken }
// Adding is two-step and enforced HERE: only an event this function proposed, unused, within 10
// minutes, can be created. `text` = spoken + "\nEVENTS:\n- …" (the app's calendar card format).
// Times use the calendar's own time zone. Primary calendar only (for now).
import { corsHeaders, json } from "../_shared/http.ts";
import { serviceClient, userClient } from "../_shared/supabaseClient.ts";
import { checkRateLimit, HOURLY } from "../_shared/rateLimit.ts";
import { claudeJson, FAST_MODEL } from "../_shared/anthropic.ts";
import { googleApi, GoogleNotConnected, localToRfc3339, nowIn, todayIn } from "../_shared/google.ts";

const CAL = "https://www.googleapis.com/calendar/v3/calendars/primary";
const DEFAULT_TZ = Deno.env.get("DEFAULT_TZ") ?? "Africa/Lagos";

interface GEvent {
  summary?: string;
  location?: string;
  htmlLink?: string;
  start?: { dateTime?: string; date?: string };
  end?: { dateTime?: string; date?: string };
}

const tzCache = new Map<string, string>();
/** The calendar's time zone (events.list reports it) — so "3pm" means 3pm where Law's calendar is. */
async function calendarTz(userId: string): Promise<string> {
  const hit = tzCache.get(userId);
  if (hit) return hit;
  const r = await googleApi(userId, `${CAL}/events?maxResults=1&timeMin=${encodeURIComponent(new Date().toISOString())}`);
  const tz = r.timeZone || DEFAULT_TZ;
  tzCache.set(userId, tz);
  return tz;
}

/** "Tue 14 Oct 10:00–11:00 · Site meeting · Trans-Amadi" for the card. */
function eventLine(e: GEvent, tz: string): string {
  const title = e.summary || "(no title)";
  const where = e.location ? ` · ${e.location}` : "";
  if (e.start?.date) {
    const d = new Date(`${e.start.date}T12:00:00Z`);
    const day = new Intl.DateTimeFormat("en-GB", { timeZone: "UTC", weekday: "short", day: "numeric", month: "short" }).format(d);
    return `${day} (all day) · ${title}${where}`;
  }
  const fmtDay = new Intl.DateTimeFormat("en-GB", { timeZone: tz, weekday: "short", day: "numeric", month: "short" });
  const fmtTime = new Intl.DateTimeFormat("en-GB", { timeZone: tz, hour: "2-digit", minute: "2-digit", hour12: false });
  const s = new Date(e.start?.dateTime ?? "");
  const en = e.end?.dateTime ? new Date(e.end.dateTime) : null;
  return `${fmtDay.format(s)} ${fmtTime.format(s)}${en ? "–" + fmtTime.format(en) : ""} · ${title}${where}`;
}

const withCard = (spoken: string, lines: string[]) =>
  lines.length ? `${spoken}\nEVENTS:\n${lines.map((l) => "- " + l).join("\n")}` : spoken;

// ── query ────────────────────────────────────────────────────────────────────────────────────────
const RANGE_SCHEMA = {
  type: "object",
  properties: {
    start: { type: "string", description: "local start, YYYY-MM-DDTHH:MM" },
    end: { type: "string", description: "local end (exclusive), YYYY-MM-DDTHH:MM" },
  },
  required: ["start", "end"],
  additionalProperties: false,
};

async function query(userId: string, text: string) {
  const tz = await calendarTz(userId);
  const range = await claudeJson<{ start: string; end: string }>(
    `You turn a question about someone's calendar into the time range to look up. Now is ${nowIn(tz)} (${tz}).
"today" = the rest of today from 00:00; "tomorrow" = that whole day; "this week" = today to the end of Sunday;
"next week" = next Monday to Sunday; a bare weekday = the next such day; "am I free at 3" = that hour on the
day meant (today if none). No date at all = today and tomorrow. Times are local wall-clock.`,
    text,
    RANGE_SCHEMA,
    { model: FAST_MODEL, maxTokens: 2000, effort: "low" },
  );
  const params = new URLSearchParams({
    timeMin: localToRfc3339(range.start, tz),
    timeMax: localToRfc3339(range.end, tz),
    singleEvents: "true",
    orderBy: "startTime",
    maxResults: "50",
  });
  const r = await googleApi(userId, `${CAL}/events?${params}`);
  const events: GEvent[] = r.items ?? [];
  const lines = events.map((e) => eventLine(e, tz));
  const { spoken } = await claudeJson<{ spoken: string }>(
    `You are JARVIS answering out loud through glasses: 1 to 3 short sentences, lead with the answer, no lists,
no markdown. Use ONLY the events given; if there are none, say the time is free. Say times naturally ("ten
a.m."), don't read out years. Now is ${nowIn(tz)}.`,
    `Question: ${text}\nLooked at ${range.start} to ${range.end}.\nEvents:\n${lines.join("\n") || "(none)"}`,
    { type: "object", properties: { spoken: { type: "string" } }, required: ["spoken"], additionalProperties: false },
    { model: FAST_MODEL, maxTokens: 2000, effort: "low" },
  );
  return { spoken, text: withCard(spoken, lines), events: events.map((e) => ({ line: eventLine(e, tz), title: e.summary ?? "", start: e.start, end: e.end, location: e.location ?? "" })) };
}

// ── propose / confirm ────────────────────────────────────────────────────────────────────────────
const EVENT_SCHEMA = {
  type: "object",
  properties: {
    ok: { type: "boolean", description: "false if no date/time can be worked out" },
    question: { type: "string", description: "if not ok: the one short question to ask" },
    title: { type: "string" },
    start: { type: "string", description: "local YYYY-MM-DDTHH:MM, or YYYY-MM-DD for all-day" },
    end: { type: "string", description: "local end; default 1 hour after start; all-day: the next day YYYY-MM-DD" },
    allDay: { type: "boolean" },
    location: { type: "string" },
    description: { type: "string" },
  },
  required: ["ok", "question", "title", "start", "end", "allDay", "location", "description"],
  additionalProperties: false,
};

interface Proposed {
  ok: boolean;
  question: string;
  title: string;
  start: string;
  end: string;
  allDay: boolean;
  location: string;
  description: string;
}

async function propose(userId: string, text: string) {
  const tz = await calendarTz(userId);
  const ev = await claudeJson<Proposed>(
    `You turn a spoken request into ONE calendar event. Now is ${nowIn(tz)} (${tz}); today is ${todayIn(tz)}.
Title: short and clear ("Site meeting with Mr Ade"), not the whole sentence. A bare weekday = the next such day.
No duration given = 1 hour. "morning" = 09:00, "afternoon" = 14:00, "evening" = 18:00. No date AND no time
→ ok=false with a short question. Location and description only if said ("" otherwise). Never invent attendees.`,
    text,
    EVENT_SCHEMA,
    { model: FAST_MODEL, maxTokens: 2000, effort: "low" },
  );
  if (!ev.ok) return { needsMore: true, spoken: ev.question || "When should I put it in?" };

  const body = ev.allDay
    ? { summary: ev.title, location: ev.location || undefined, description: ev.description || undefined, start: { date: ev.start.slice(0, 10) }, end: { date: (ev.end || ev.start).slice(0, 10) } }
    : { summary: ev.title, location: ev.location || undefined, description: ev.description || undefined, start: { dateTime: localToRfc3339(ev.start, tz), timeZone: tz }, end: { dateTime: localToRfc3339(ev.end || ev.start, tz), timeZone: tz } };
  if (ev.allDay && body.end.date === body.start.date) {
    const d = new Date(`${body.start.date}T00:00:00Z`);
    d.setUTCDate(d.getUTCDate() + 1);
    body.end = { date: d.toISOString().slice(0, 10) };
  }
  const line = eventLine(body as GEvent, tz);
  const { data, error } = await serviceClient().from("pending_actions")
    .insert({ user_id: userId, kind: "calendar_add", payload: body }).select("id").single();
  if (error) throw new Error(`couldn't hold the event for confirmation: ${error.message}`);
  const spoken = `Shall I add ${ev.title}, ${line.split(" · ")[0]}${ev.location ? ", at " + ev.location : ""}?`;
  return { id: data.id, spoken, text: withCard(spoken, [line]), event: body };
}

async function confirm(userId: string, id: string) {
  const db = serviceClient();
  // Claim it atomically: only an unused, unexpired proposal of THIS user can be used, once.
  const { data, error } = await db.from("pending_actions")
    .update({ used_at: new Date().toISOString() })
    .eq("id", id).eq("user_id", userId).eq("kind", "calendar_add")
    .is("used_at", null).gt("expires_at", new Date().toISOString())
    .select("payload").maybeSingle();
  if (error) throw new Error(error.message);
  if (!data) return { spoken: "That request has expired. Ask me again and I'll set it up.", expired: true };
  const created = await googleApi(userId, `${CAL}/events`, { method: "POST", body: JSON.stringify(data.payload) });
  const tz = await calendarTz(userId);
  const spoken = "Done, it's on your calendar.";
  return { spoken, text: withCard(spoken, [eventLine(created, tz)]), link: created.htmlLink ?? "" };
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  const t0 = Date.now();
  try {
    const supabase = userClient(req);
    const { data: { user } } = await supabase.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);
    if (!await checkRateLimit(supabase, "calendar", 60, HOURLY)) {
      return json({ error: "Hourly calendar limit reached — try again shortly." }, 429);
    }
    const { op, text, id } = await req.json();
    const q = String(text ?? "").trim();
    let out: Record<string, unknown>;
    switch (op) {
      case "query":
        if (!q) return json({ error: "text is required" }, 400);
        out = await query(user.id, q);
        break;
      case "propose":
        if (!q) return json({ error: "text is required" }, 400);
        out = await propose(user.id, q);
        break;
      case "confirm":
        if (!id) return json({ error: "id is required" }, 400);
        out = await confirm(user.id, String(id));
        break;
      case "cancel":
        if (id) {
          await serviceClient().from("pending_actions").update({ used_at: new Date().toISOString() })
            .eq("id", String(id)).eq("user_id", user.id).is("used_at", null);
        }
        out = { spoken: "Okay, I won't add it." };
        break;
      default:
        return json({ error: "op must be query | propose | confirm | cancel" }, 400);
    }
    return json({ ...out, ms: Date.now() - t0 });
  } catch (e) {
    if (e instanceof GoogleNotConnected) return json({ error: e.message, notConnected: true, spoken: e.message }, 409);
    console.error("calendar error:", e);
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
