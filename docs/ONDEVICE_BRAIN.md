# On-device "first brain" — research brief (PRE-DECISION, for discussion)

*Director's idea (2026-06-14): a small AI model on the phone that handles simple tasks on-device, and
escalates heavy/uncertain ones to the "second brain" (cloud API + the Agent Bridge). This is a research
brief to brainstorm — **nothing is being built yet.** Companion to `docs/ASSISTANT_ROADMAP.md` (which
already lists an "Offline Pack" large LLM as deferred-by-design) and the Two-Speed Brain decision.*

## 1. The idea, named
This is a **tiered / cascading inference** architecture — the same pattern as **Apple Intelligence**
(on-device model + Private Cloud Compute) and LLM "routing/cascade" systems: a cheap, fast, local model
fields what it can; a bigger remote model handles the rest. For JARVIS it becomes a **Three-Speed
Brain**:
- **Brain 0 — on-device (new):** instant, offline, private, free. Simple NLU + small answers + routing.
- **Brain 1 — cloud API (today's fast lane):** Claude over the internet + RAG memory. The default.
- **Brain 2 — Agent Bridge (deliberate lane):** Claude Code, tools, multi-step (research/code/email).

## 2. What JARVIS already runs on-device (we're not starting from zero)
- **STT:** sherpa-onnx Whisper-tiny (offline, ~0.4 s/turn).
- **Wake word:** Vosk ("Jarvis").
- **Embeddings:** MediaPipe Universal Sentence Encoder → offline semantic recall.
- **JarvisLite:** a **rule-based** off-grid answerer (phrases answers from recalled memories).
So Brain 0 partly exists — but it's *rules*, not a real generative model. The new piece is a small
**on-device LLM**.

## 3. The tech (researched 2026-06)
- **Runtime:** Google's **LiteRT-LM** (Kotlin) is now the recommended Android on-device LLM API;
  **MediaPipe LLM Inference is maintenance-only** (we already use MediaPipe for embeddings, so this is
  familiar ground). Alternatives: llama.cpp (GGUF) or MLC-LLM.
- **Models that fit a phone:** **Gemma 3 1B** (4-bit) is the sweet spot — runs at high token rates on a
  mobile GPU and scores ~63% GSM8K; **Gemma 3n E2B/E4B** add small-multimodal; Phi-mini (~3.8B) and
  Qwen-small are options. 2026 flagships handle up to ~4B Q4 on-device.
- **The director's device — Pixel 8 (Tensor G3, 8 GB):** comfortably runs **1B–2B Q4** (≈0.5–1.5 GB
  model, download-gated like our Whisper model already is); 3–4B is possible but tighter on RAM/heat.
  Expect usable interactive speed for short turns; long context is slower.

## 4. The hard constraint: TRUTH (this shapes everything)
JARVIS's SOUL charter is **never fabricate; say "I don't know," then go find out.** A 1B–2B model
**hallucinates far more than Claude** and has tiny world-knowledge. So Brain 0 must be scoped to tasks
where it can't lie about facts:
- ✅ **Good for Brain 0:** intent classification/routing (is this chat / vision / research / calendar /
  email / a command?), wake/closing detection, simple rewrites & summaries **of text we already have**,
  date/time and unit math, extracting fields from an utterance ("add lunch **tomorrow at noon**"),
  phrasing an answer **from retrieved memories** (an LLM upgrade to JarvisLite), short canned chit-chat.
- ❌ **Must NOT be Brain 0:** open-ended factual Q&A, anything time-sensitive, anything the user will
  treat as authoritative. Those go to Brain 1/2 (with web verification).
The safest framing: **Brain 0 is a router + a language *surface* over our own data — not a knowledge
source.** That keeps it inside the truth charter.

## 5. How routing could work (options to discuss)
- **(a) Heuristic router (cheapest):** keep today's regex/intent detection; add the on-device LLM only
  as the *off-grid* answerer (replace JarvisLite's templating). Lowest risk, smallest win.
- **(b) On-device classifier:** the small model decides per turn: "answer locally / needs cloud / needs
  a tool", emitting a structured tag. More flexible; needs careful prompting + a confidence floor that
  defaults to escalate.
- **(c) Confidence cascade:** Brain 0 attempts; if its self-estimated confidence is low (or the user
  query looks factual/fresh), escalate to Brain 1, then Brain 2. Best UX, most complex; risk = a
  confident-but-wrong local answer (truth charter violation) if the floor is too low.
**Lean:** start at **(a)** — biggest safety, real offline benefit — and only move toward (b)/(c) if the
on-device model proves reliable as a *router* (which is a classification task it can actually do well).

## 6. What it buys us / what it costs
**Buys:** works in a tunnel/plane (true offline conversation, not just rule templates); lower latency on
trivial turns; lower cloud cost; privacy for simple stuff; graceful degradation.
**Costs:** +0.5–1.5 GB download + RAM/heat/battery during inference; quality ceiling (small model);
another model to maintain (LiteRT-LM is new + evolving); added routing complexity; **the truth risk** if
mis-scoped. Maintenance burden is real — MediaPipe→LiteRT churn shows the space moves fast.

## 7. Open questions for the director (let's talk)
1. **Primary goal?** Offline capability, lower latency, lower cost, or privacy — which matters most?
   (It changes the design.)
2. **Scope for Brain 0:** comfortable with "router + surface over our data, never a fact source"
   (recommended), or do you want it answering general questions offline too (higher truth risk)?
3. **Footprint:** OK with another ~1 GB download (like the Whisper model), download-gated/optional?
4. **When:** is this a now-thing, or after the agent lanes are fully untethered (tunnel) + FCM land?
5. **Device target:** optimize for your Pixel 8, or keep it general (affects model size choice)?

## 8. Suggested next step (only if you want to proceed)
A tiny **spike**, not a commitment: drop Gemma 3 1B (Q4) behind a feature flag via LiteRT-LM, wire it as
**(a)** the off-grid answerer (replacing JarvisLite templating) and measure on the Pixel 8 — load time,
tokens/s, RAM, battery, and answer quality on a fixed set of simple prompts. Decide from real numbers
whether to extend it to routing (b/c). No engine changes until the spike's numbers are in.

## 9. Director direction + refined router (2026-06-14)
**Director's call:** Brain 0 is a **router**; the on-device LLM is used for (a) routing, (b) **offline /
no-network** scenarios, and (c) **mundane/simple** questions. **Priority order: LATENCY first, then
COST, then other factors.**

That priority order has sharp design consequences:

1. **The router must be ~instant — so it is NOT an LLM call.** If we used the small model to *classify*
   difficulty, that classification is itself latency (load + inference) on every turn — the opposite of
   the goal. So **rank difficulty with a cheap heuristic** (microseconds) computed from signals we
   already have, and only *run* the on-device LLM once we've decided to answer locally.
   - **Difficulty/route signals (instant):** intent type (we already detect command vs Q&A vs chit-chat);
     utterance length; factual/temporal markers ("today", "latest", "price", "who/when/how many");
     whether a memory-recall hit is high-confidence (answer is in *our* data); personal-vs-world; and
     **network availability**.

2. **Difficulty ≠ safety — rank on BOTH.** "Simple/mundane" is not the same as "safe to answer
   on-device." "What's the population of X?" is *simple* but a 1B model will **confidently hallucinate**
   it (truth-charter violation). So the router scores **difficulty AND factual-risk**: on-device only
   takes a turn when it's **(offline) OR (non-factual: chit-chat, rewrite/summarize given text, a
   command) OR (grounded: the answer is in our memory/profile)**. Anything world-factual goes to the
   cloud even if "easy" — unless we're offline (then best-effort + clearly flagged).

3. **Latency reality (worth measuring, not assuming):** on-device skips the network RTT (~0.3–1 s) but a
   1B model generates slower per token. So **on-device wins on SHORT answers and offline; cloud
   streaming wins on longer/complex** (fast first word, then streams). Route by difficulty *and expected
   length*. Also: a **cold model load is seconds** — to get the latency win we must keep the on-device
   model **warm/resident** (a RAM cost), or accept lazy-load latency on the first use.

4. **Cost lever (2nd priority) rides along:** on-device = free, so routing simple→local cuts cost too
   (aligned with latency). And when we *must* hit the cloud, the router can pick the **model tier by
   difficulty** — Haiku (cheap/fast) for simple-needs-network, Sonnet for moderate, rare Opus for hard
   — which is itself a latency+cost win and uses our existing tiered-models decision.

**Proposed router (latency-first):**
```
on each turn (after STT):
  if command intent (remember/vision/calendar/email/research/coding) -> existing handler / Brain 2
  else compute {difficulty, factualRisk, hasMemoryHit, online} instantly
  if !online            -> Brain 0 (on-device, best-effort, flagged "offline")
  else if factualRisk    -> Brain 1 cloud (model tier by difficulty), streaming
  else if simple && (chit-chat || hasMemoryHit) -> Brain 0 (fast, free)
  else                   -> Brain 1 cloud, streaming
```

**Reality check on the latency win:** today's biggest latency costs are STT (already on-device ~0.4 s) +
network + endpointing, and the cloud path already **streams** (quick first word). So Brain 0's latency
gain is **targeted**: trivial turns, offline, and avoiding a wasted cloud round-trip for things we can
answer from our own data. Real but not universal — which is why the next step is **measurement, not a
big build.**

**Next step (proposed): a flagged spike, measure on the Pixel 8 —** Gemma 3 1B (Q4) via LiteRT-LM, kept
warm, wired first as the **offline answerer + a grounded "answer from memory" responder**; measure
cold-load, warm tokens/s, time-to-first-word for short answers, RAM, battery, and the **latency
crossover** vs cloud streaming by answer length. Those numbers decide whether/how far to extend the
router. No engine changes until the spike's numbers are in.

**Open for you:** (1) keep the on-device model **warm/resident** for the latency win (accept the RAM),
or lazy-load? (2) for *world-factual but simple* questions while **online**, always prefer cloud
(safe), yes? (3) do the spike now, or after the tunnel + FCM land?

**Status:** brainstorm/research only (2026-06-14); director direction captured (router; latency>cost).
No build started; next is a measured spike on director's go-ahead.

## Sources
- [MediaPipe / LiteRT LLM Inference guide (Android)](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android)
- [Small Language Models in 2026 (Phi-4, Gemma 3 on-device)](https://masterprompting.net/blog/small-language-models-phi4-gemma-on-device-2026)
- [Gemma edge deployment (E2B/E4B on phones)](https://www.mindstudio.ai/blog/gemma-4-edge-deployment-e2b-e4b-models)
- [Small Language Models guide 2026 (8 GB RAM)](https://localaimaster.com/blog/small-language-models-guide-2026)
