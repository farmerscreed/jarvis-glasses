# JARVIS — "Chief of Staff" build roadmap (v1 → v2)

*The path and strategy for turning JARVIS from a voice companion into the director's **chief of
staff**: an assistant that is **extremely truthful, never hallucinates, says "I don't know" when it
doesn't — and then goes and finds out**, and that knows the director and itself deeply. Companion to
`docs/ASSISTANT_MEMORY.md` (the Hermes/OpenClaw research this is built on).*

## North star (director, 2026-06-13)
- **Truth above all.** Never fabricate. If unsure, say so plainly. Prefer "I don't know" to a
  confident guess. Distinguish what it *knows* (memory/retrieved facts) from what it *infers*.
- **Resourceful.** When it doesn't know but the answer is findable, it **goes and finds it** (skills:
  web/research, tools, the memory index) instead of guessing or giving up.
- **Knows the director + itself.** A curated, always-on profile (USER) + a defined character (SOUL).
- **Acts on the director's behalf** — a true chief of staff: tracks commitments, drafts, researches,
  reminds, executes delegated tasks.

---

## v1 — the truthful, self-knowing memory foundation (build now)

Goal: JARVIS reliably **knows who the director is and who it is**, **tells the truth / admits
ignorance**, and **remembers the right things** (distilled, not hoarded). No autonomous task execution
yet — that's v2 — but the honesty + memory bedrock everything else stands on.

### v1.1 Profile layer (storage + always-on injection)  ← FIRST
- **Storage:** a `profile` table in Postgres (one row per user): `soul` (JARVIS's character),
  `user_facts` (curated facts about the director), `updated_at`. RLS like every other table.
- **Injection:** `chat` and `chat-stream` prepend SOUL + USER_FACTS to the system prompt, **above** the
  retrieved memories, every turn. This is what makes it know-the-user/have-character in *all* answers.
- **Truthfulness charter in the system prompt:** explicit rules — ground answers in the provided
  memories/profile; if the answer isn't there, say "I don't know" and (v2) offer to find out; never
  invent specifics (names, numbers, dates); separate known fact from inference.

### v1.2 Distillation (key facts, not verbatim hoarding)
- Today the backend saves **every** Q&A as `type:"qa"` → clutter. Replace with a **post-conversation
  distill pass:** an LLM call over the transcript — *"list any durable facts, preferences, or
  corrections about the user; else nothing"* — that **merges** results into `profile.user_facts`
  (substring add/replace, Hermes-style). Raw turns kept only for session search, demoted.
- **Decision tree** (Hermes): correction? preference? durable fact? else easily rediscovered → skip.

### v1.3 Memory tool + consolidation
- Give the model `memory.add/replace/remove` so it can curate mid-conversation ("remember I parked on
  level 3"). Consolidate `user_facts` when it grows past a bound (merge/compress/drop stale).

### v1.4 Self-improvement from corrections
- When the director corrects JARVIS, the correction updates `user_facts` immediately (so it's never
  repeated) and is weighted over the stale fact.

### v1.5 Editable profile (director control)
- A Settings screen (and/or a `profile` Edge Function GET/PUT) to **view and edit** SOUL + USER_FACTS,
  so the director can hand-tune the character and correct facts directly. Seeded from the SOUL.md draft.

**v1 done = JARVIS, in every answer, knows the director + itself, tells the truth / admits ignorance,
and keeps a clean distilled profile the director can edit.**

---

## v2 — the acting chief of staff (skills, autonomy, reach)

Goal: it doesn't just *know* — it **does**, truthfully and on the director's behalf.

### v2.1 Skills (the "goes and finds out" layer)
- A skill = a reusable, declared procedure the model can invoke (tool-use): **web research /
  search**, calculations, reading the director's data, calendar/email actions, price-checks, etc.
- The honesty loop closes here: "I don't know" → **pick a skill → find the answer → report with
  sources**, instead of guessing. (Mirrors the V2 "Ask Jarvis" deliberate lane already in the roadmap.)
- Skill results that are durable get distilled into memory.

### v2.2 Autonomy — crons / heartbeat (the "noticer")
- A scheduled background loop (Hermes "crons" / OpenClaw `HEARTBEAT.md`): proactive reminders,
  follow-ups on open commitments, daily briefings, watching for things the director cares about.
  Builds on the planned FCM/noticer + foreground service.

### v2.3 Reach / delegation
- Send JARVIS to do a task and have it report back across channels (push, later messaging). True
  "send it to do things for me."

### v2.4 Trust & safety for an acting agent
- Confirmation gates for outward/irreversible actions; an audit log of what it did on the director's
  behalf; truthful reporting of failures ("I tried X, it failed because Y") — never silent or faked.

---

## Sequencing & status
1. **v1.1 profile layer** — building now (migration + chat injection + SOUL/USER seed).
2. v1.2 distillation → v1.3 memory tool → v1.4 self-improvement → v1.5 editable profile.
3. Then v2.1 skills (highest-value agentic step) → v2.2 crons → v2.3 reach.

Cloud deploys (migration + function changes) are director-authorized per the Phase E pattern. Each
increment is committed + verified before the next.

## Open decisions (carried from ASSISTANT_MEMORY.md)
- Profile storage = dedicated `profile` table (chosen).
- Distillation cadence = end of conversation (chosen, cheaper).
- Keep raw Q&A for session search but lead with distilled facts (chosen).
- **SOUL.md v1 drafted for the director to edit** (see `docs/assistant/SOUL.md`).
