# JARVIS as a dependable personal agent — memory architecture (Hermes / OpenClaw study + plan)

*Opened 2026-06-13. Director's vision: JARVIS becomes a **dependable personal assistant with
character** that acts on the director's behalf (research, tasks, errands) — which requires it to know
a lot **about the director** and **about itself**. Step 1 is the memory foundation. This doc studies
how **Hermes Agent** (Nous Research) and **OpenClaw** do memory and proposes how to replicate it on
JARVIS's existing Postgres+pgvector core.*

## What Hermes & OpenClaw actually do (they converge on one pattern)

**Both** treat memory not as "stuff you fetch when needed" but as **who the agent _is_** — curated,
bounded, and always in the system prompt. Key mechanics:

**Hermes Agent** — five pillars (Memory, Skills, Soul, Crons, Self-improvement). Memory =
- **Durable files, always-on, bounded.** `MEMORY.md` (~2.2 KB: environment/project facts, tool
  quirks, workflows) + `USER.md` (~1.4 KB: who the user is — role, preferences, communication style,
  pet peeves, timezone). Together <1,300 tokens, **injected into the system prompt at session start**
  and frozen (so the prefix is cache-friendly). *"Memory isn't something the agent retrieves; it's
  something the agent **is** — built into the system prompt, curated, bounded, always active."*
- **A `memory` tool**: `add` / `replace` / `remove` (substring match — no exact text needed).
- **Distillation + consolidation**: entries are dense; when memory is >80% full the agent **merges
  related entries, drops stale facts, compresses.** A decision tree gates writes: *is it a correction?
  a preference? an environment fact? else — can it be rediscovered easily? if yes, skip it.*
  "Unlimited memory is a liability… forgetting is a feature."
- **Session search** (on-demand): SQLite **FTS5** over past conversations + **LLM summarization**
  (Gemini Flash) — "did we discuss Docker last week?"
- **Soul** = persona/character; **Crons** = scheduled autonomy; **Self-improvement** = observe →
  discover preferences/corrections → consolidate into the durable files.

**OpenClaw** — local-first, **plain Markdown/YAML** memory you can git-back/grep/delete. Writes
**daily notes → distills key insights → recalls weeks later via semantic search**. `HEARTBEAT.md`
checklist drives an autonomous daemon that acts without prompting. Skills via a registry (ClawHub).

**The shared recipe:** (1) a small, curated, **always-in-context profile** (user + self), (2) **write
selectively and distill** — never dump everything, (3) **consolidate/forget** to stay bounded, (4) a
separate **searchable episodic log** for "what did we say back then", (5) **skills** = procedures
(separate from facts), (6) a **persona/soul**, (7) a **self-improvement loop** from corrections.

## Where JARVIS stands vs that recipe

| Recipe element | JARVIS today | Gap |
|---|---|---|
| Episodic, searchable memory | ✅ Postgres + **pgvector**, `match_memories` RAG in `chat`/`chat-stream` | (have the hard part) |
| Always-on curated **user profile** | ❌ system prompt is generic ("You are JARVIS, a concise voice companion") + retrieved memories | **build this** |
| **Self / persona** ("about itself") | ❌ none beyond one line | **build this** |
| **Distillation** ("key memory") | ❌ saves **every Q&A verbatim** (`type:"qa"`) → clutters recall (director flagged) | **build this** |
| Bounded / consolidation | ❌ index grows unbounded | build later |
| Session search w/ summarization | 🟡 semantic recall exists; no summarize step | minor |
| Skills (procedures) | 🟡 app capabilities exist; no skills framework | V2 agentic lane |
| Crons / heartbeat autonomy | 🟡 roadmap "noticer"; FCM planned | V2 |

**JARVIS already has the expensive part (vector memory).** What's missing is the cheap, high-leverage
part Hermes/OpenClaw nail: a **curated always-on profile of the user + self**, and **distilling** what's
worth keeping instead of hoarding raw transcripts.

## Proposed design (maps onto the existing stack — no rip-and-replace)

**1. Durable profile layer (the big win).** Add per-user, always-in-context, bounded docs:
- `USER.md` — identity, preferences, people/places that matter, communication style, corrections.
- `SOUL.md` — who JARVIS is: character, how it acts on the director's behalf, tone, boundaries.
Store as rows (e.g. `profile` table, or `memories` with `type='profile'|'persona'`, one current
version each). **Inject both into the `chat`/`chat-stream` system prompt** alongside the RAG hits.
Keep each bounded (~1–2 KB) so it's cache-friendly and forces selectivity.

**2. Distillation instead of verbatim hoarding (the "key memory" the director asked for).** Replace
"insert every Q&A" with a **post-turn/post-conversation extraction pass**: an LLM call —
*"From this exchange, list any durable facts, preferences, or corrections about the user; if none,
return nothing"* — and `add`/`replace` those into `USER.md` (substring-merge, like Hermes). Raw turns
either stop being saved or are kept only for session search, demoted below the curated facts.

**3. A memory tool + decision tree.** Give the model `memory.add/replace/remove` so it can curate
during a conversation ("remember that I parked on level 3" → a real entry), gated by the
correction/preference/fact decision tree. Consolidate when the profile grows.

**4. Self-improvement loop.** User corrections ("no, my flight is at gate 42") update `USER.md`
immediately, so they're not repeated.

**5. Later — Skills, Crons/heartbeat, multi-channel.** The agentic "go do things for me" layer
(reusable skills + scheduled autonomy + reach beyond the glasses) builds on top once the memory
foundation is solid. This is the path to "send it to do things for me."

## Order of work (foundation first, per director)
1. **Profile layer** — `USER.md` + `SOUL.md`, injected into the system prompt. *(biggest immediate
   character/knowledge gain; small change.)*
2. **Distillation** — extract key facts after a conversation; stop hoarding verbatim Q&A.
3. **Memory tool + consolidation** — let JARVIS curate/forget; keep it bounded.
4. **Self-improvement** — corrections update the profile live.
5. **(V2) Skills + crons/heartbeat + multi-channel** — the act-on-my-behalf agent.

## Decisions for the director
- **Profile storage**: a dedicated `profile` table vs special `memories` rows. (Lean: `profile` table
  — clean, one current version of each doc, easy to inject.)
- **Distillation cadence**: after every turn (fresh but more LLM calls) vs end of conversation
  (cheaper, batched). (Lean: end of conversation.)
- **Keep raw Q&A** for session search, or go fully distilled? (Lean: keep raw but demote; distilled
  facts lead.)
- Who authors `SOUL.md` v1 — the director describes the character they want, I draft it.

## Sources
- Hermes Agent memory: [glukhov.org](https://www.glukhov.org/ai-systems/hermes/hermes-agent-memory-system/),
  [five pillars (MindStudio)](https://www.mindstudio.ai/blog/hermes-agent-five-pillars-memory-skills-soul-crons)
- OpenClaw: [Milvus guide](https://milvus.io/blog/openclaw-formerly-clawdbot-moltbot-explained-a-complete-guide-to-the-autonomous-ai-agent.md),
  [neurohive](https://neurohive.io/en/guides/openclaw-the-lobster-that-took-over-the-world-how-one-developer-built-the-most-popular-open-source-ai-agent-in-history/)
- Agent memory patterns 2026: [mem0 state of memory](https://mem0.ai/blog/state-of-ai-agent-memory-2026),
  [Letta/MemGPT tiered memory (atlan)](https://atlan.com/know/agent-memory-architectures/)
