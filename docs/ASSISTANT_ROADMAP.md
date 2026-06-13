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

### v2.1 Skills (the "goes and finds out" + "does things" layer)
- A skill = a reusable, declared procedure the model can invoke (tool-use). The honesty loop closes
  here: "I don't know" → **pick a skill → find the answer → report with sources**, instead of guessing.
  Skill results that are durable get distilled into memory. Implemented as Anthropic tool-use in the
  chat path (the v1.3 explicit-remember was a deliberately simple stand-in for the full tool loop).
- **Voice-controlled glasses (director ask, 2026-06-13) — high priority.** Mid-conversation voice
  commands that drive the hardware: *"what am I looking at"* → take a photo → describe it; *"record
  this"* / *"take a photo of this"* / *"look at this and …"*. JARVIS already has the camera/BLE/Wi-Fi
  capture pipeline (`GlassesCaptureReactor`, Look&Ask vision); this wires **voice intent → capture →
  vision/transcribe → spoken answer**, all hands-free in the conversation loop. A natural first skill.
- **Information & work skills (director ask, 2026-06-13):** **web browsing/research**, **document
  editing**, **coding**, calculations, calendar/email, price-checks, reading the director's data.
- **Leverage Claude Code (brainstorm, 2026-06-13):** explore using the existing Claude Code / Agent
  SDK as the engine for the heavier "do things" skills (research, code, doc edits) — JARVIS as the
  voice/glasses front-end delegating deliberate tasks to a Claude Code agent backend, which already
  has tools (browsing, files, shell, MCP). Open design question: hosted agent the app calls vs. the
  deliberate "Ask Jarvis" lane. To brainstorm before building.

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
1. **v1.1 profile layer — ✅ DEPLOYED + VERIFIED (2026-06-13).** `profile` table migrated to prod;
   `chat`/`chat-stream`/`profile` deployed; SOUL seeded into the director's profile. Verified live:
   asked "who are you + my daughter's name" → introduced as chief of staff and said *"I don't know it
   yet, you haven't told me"* (character + truth charter both working).
2. **v1.2 distillation — ✅ DEPLOYED + VERIFIED.** `distill` deployed; app fires it at conversation
   end. Verified: a test convo distilled to clean bullets (`Daughter: Maya`, `Prefers concise answers`)
   and the next answer recalled them. (Test facts cleared afterward; profile starts clean.)
3. **v1.3 explicit memory — ✅ BUILT.** `remember` function + app detection: "remember that/to/this…"
   pins a fact immediately (no LLM round-trip) and confirms "Noted." (`isRememberCommand`). Same-
   conversation recall already works via history threading; durable via distillation; this adds the
   reliable explicit pin. *(Full model-invoked tool-use deferred to v2.1 — it shares the tool loop.)*
4. **v1.4 self-improvement — ✅ covered** by the distill merge (it applies corrections to user_facts).
5. **v1.5 editable profile — ✅ BUILT.** Settings → "JARVIS's memory": view/edit SOUL + curated facts,
   Save (`getProfile`/`setProfile` + `profile` function). The director edits the character in-app.
6. Then **v2.1 skills** — incl. the **voice-controlled glasses** + browsing/doc/coding tools +
   Claude Code leverage (see v2 below) → v2.2 crons → v2.3 reach.

Cloud deploys (migration + function changes) are director-authorized per the Phase E pattern. Each
increment is committed + verified before the next.

## Open decisions (carried from ASSISTANT_MEMORY.md)
- Profile storage = dedicated `profile` table (chosen).
- Distillation cadence = end of conversation (chosen, cheaper).
- Keep raw Q&A for session search but lead with distilled facts (chosen).
- **SOUL.md v1 drafted for the director to edit** (see `docs/assistant/SOUL.md`).
