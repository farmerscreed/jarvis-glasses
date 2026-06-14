# UI/UX integration plan — homing the new features in the Stitch design

*How the features shipped since the UI pass (agent delegation lanes, voice-vision, battery, research
viewing, on-device "Brain 0", confirm gates, push) map onto the existing **Stitch "Aetheric
Intelligence"** design system (`docs/design/stitch_jarvis_companion_design_system/`) and into the live
app. Planning/brainstorm — nothing built from this yet. Written 2026-06-14. "Documentation is key."*

## 0. Where the UI is today (baseline)
Built + device-verified (the UI pass, 2026-06-12): `JarvisTheme` (Aetheric tokens), the animated
**PresenceOrb** (idle/listening/thinking/speaking/deliberating/off-grid), and four tabs —
**Live / Timeline / Gallery / Settings** — plus Help center + onboarding. The new engine features
since then mostly surface as **plain text in the Live console transcript** + a status-line string.
That's the gap: the design has richer, purpose-built surfaces for exactly these features.

## 1. Feature → design screen → integration (the map)
| New feature (shipped) | Designed screen (exists in `docs/design/…`) | Today | Integration |
|---|---|---|---|
| **Agent lanes** (research/coding/email/calendar) + **voice-vision** | `ask_jarvis_multimodal_conversation`, `ask_jarvis_premium_conversation_polish` | text in Live transcript | **New "Ask JARVIS" deliberate-lane surface** (§2) — the big one |
| **Glasses battery** (decoded) | `device_jarvis_glasses` (shows **84%** + Wi-Fi-Direct + Buttons Map + Connection Doctor + storage/latency) | status-line "glasses NN%" | Build the **Device screen** under Settings; move battery there (keep the status-line chip) |
| **Research viewing** (full text) | `memory_detail_photo`/`_voice_note`, `timeline_search_results` | Timeline card, tap-to-expand | A **Research filter/section** in Timeline + a proper **memory detail** screen |
| **On-device "Brain 0"** (router; which brain answered) | `presence_orb_visual_evolution`, status dots | n/a | A small **"which brain" chip / orb tint** (on-device vs cloud vs agent) |
| **Confirm-before-act** (commit, calendar-add) | `trust_privacy_indicators` | spoken yes/no only | An on-screen **confirm sheet** mirroring the spoken gate |
| **Async tasks + push** (M4, when FCM lands) | `notification_system_showcase`, `captures_in_progress_sync` | n/a | **Task/notification** surfacing: "researching…", "done" push, a task list |
| **OCR / translation / meeting** (future skills) | `ocr_read_it_to_me`, `live_translation_console`, `meeting_strategy_sync` | n/a | Build alongside each skill when it lands |

> ✅ **UI-2 v1 BUILT (2026-06-14):** `AskJarvisScreen.kt` — a deliberate-lane surface reached from the
> Live console (the ✨ button, not a 5th tab). Text input dispatches to the agent lanes
> (research/calendar/email/coding) via `HomeViewModel.askJarvis()`, renders a reviewable **thread of
> result cards** (by kind), pins the **latest photo as context**, and **confirms-before-act** for
> calendar-add + commit (on-screen dialog mirroring the spoken gate). Off-agent text falls back to chat.
> Built dev+prod; **install/on-device verify pending** (phone disconnected). Next: richer per-kind
> cards (research Sources disclosure, calendar list, email draft preview) + optional voice input here.

## 2. The headline: an "Ask JARVIS" deliberate-lane surface
`ask_jarvis_multimodal_conversation` is purpose-built for everything we just shipped on the heavy lane.
What the design shows and how it maps:
- **Pinned-context card (top):** a thumbnail + label of the thing being discussed — **this is voice-
  vision**: "what am I looking at" captures a photo, it pins as context, and the conversation continues
  *about that photo*. (Solves the "discuss a photo" UX the V2 "Ask Jarvis" gate was waiting on.)
- **Structured tool-result cards:** the mock shows a "PRICE ANALYSIS" card (ranked options, "BEST
  OPTION" highlighted). This is the render target for **agent results** — research → a sourced summary
  card (with a Sources disclosure), calendar → an events list card, email → a draft-preview card,
  coding → a files-changed/diff-summary card. Far better than today's wall of spoken text.
- **Multi-turn thread + "Ask JARVIS…" input + mic:** the deliberate lane is conversational and patient
  (vs the fast Live console). Same orb language, different surface.

**Why a separate surface (not just the Live console):** the Live console is the *fast reflexive* lane
(speak → answer, ephemeral). The deliberate lane is *patient + visual + reviewable* (tool results,
pinned media, async tasks you come back to). The Two-Speed Brain deserves two surfaces. The orb's
**"deliberating"** state already exists for this.

**Engine side is mostly ready:** `AgentBridge` returns structured-ish text + `AgentResult`; voice-vision
returns a description + saves the photo. To render rich cards we'd return slightly more structure from
the agent presets (e.g. ask the research preset for a short JSON block alongside the spoken summary) and
add Compose card composables. **No engine rewrite — additive.**

## 3. Quick wins (small, high-value, do first)
1. ✅ **DONE (2026-06-14) — Device screen** (`device_jarvis_glasses`) under **Settings → Glasses &
   device** (`DeviceScreen.kt`): connection state, the **battery %** we now decode, the decoded
   **Buttons Map** (front=photo, BACK-hold=record, BACK-double=Look&Ask), and a **"Fix connection"**
   action (re-runs the sync ceremony). Settings row shows the live battery inline. Built + installed
   (dev+prod), launches clean. *Needs an on-device look + battery-vs-real check.*
2. ✅ **DONE (2026-06-14) — Research in Timeline:** a **"Research" filter chip** (tag=`research`) on the
   Timeline + the existing **tap-to-expand** cards → the director's "place to read the full research".
   (A dedicated `memory_detail` screen remains optional polish.)
3. ⏳ **Deferred — "Which brain" indicator:** waits on **Brain 0** actually being wired (today it's only
   the dev-console spike). When routing is live, add a chip/orb-tint: on-device (amber) / cloud (cyan) /
   agent (deliberating). Reinforces the truth posture (user sees when an answer came from the small
   local model vs verified cloud).

## 4. Suggested sequencing (for discussion)
- **Phase UI-1 (quick wins):** Device screen + battery, which-brain chip, Research filter/detail.
- **Phase UI-2 (the deliberate lane):** the "Ask JARVIS" surface — start text-thread + pinned-photo
  context, then add tool-result cards (research first, then calendar/email/coding).
- **Phase UI-3 (async + trust):** the confirm sheet (mirror the spoken gate) and, once FCM lands, the
  task list + "done" notification (`notification_system_showcase`).
- Translation/OCR/meeting consoles: build with their skills (not yet implemented).

## 5. Principles to keep (from `aetheric_intelligence/DESIGN.md`)
Dark "Base Ink" canvas; **Electric Cyan** = live/cloud/active, **Semantic Amber** = local/offline/
pending (→ use amber for on-device Brain 0 + offline); tri-font (Space Grotesk / Inter / JetBrains Mono
for transcripts+data); XL rounding + glassmorphism; **content only when summoned** (voice-first);
status dots (amber local / cyan cloud). All already encoded in `JarvisTheme` — new screens reuse it,
**no engine/ViewModel changes for the skin** (the established rule from the UI pass).

## 6. Open questions for the director
1. **Deliberate lane as a 5th tab** ("Ask"), or entered from the Live orb / a long-press? (5 tabs is a
   lot; a dedicated entry may be cleaner.)
2. **Tool-result cards now, or text-thread first?** (Cards need a bit more structure from the agent
   presets; text-thread + pinned photo is faster to ship.)
3. **Priority order** — do the **quick wins** (device/battery, which-brain, research) first, or go
   straight at the **Ask JARVIS** deliberate-lane surface (the biggest UX upgrade)?
4. Should the **on-device Brain 0 indicator** be explicit (a chip the user reads) or subtle (orb tint)?

**Status:** plan/brainstorm only (2026-06-14). No UI built from this yet; awaiting director direction on
sequencing + the open questions.
