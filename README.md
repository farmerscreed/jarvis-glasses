# jarvis — AI Companion build repo (AIMB-G2 glasses)

Build repo for the JARVIS/ECHO AI companion. The product brief, device recon, and the
full implementation plan live in `..\Jarvis Glasses\` (read `00_HANDOFF_START_HERE.md`
and `01_IMPLEMENTATION_PLAN.md` there first).

## Status
- **Phase 0A (backend) — DONE (local).** Local Supabase on Docker with the `memories` Personal
  Memory Index (pgvector + RLS + `match_memories` RAG function), verified end-to-end (lint + advisors clean).
- **Phase 0B (Android skeleton) — DONE.** 8-module Kotlin/Compose project in `android/` — builds
  to a debug APK (`:app:assembleDebug` verified). See `android/README.md`.
- **Edge Functions (`ingest`/`recall`/`chat`) — VERIFIED end-to-end.** RAG works live: store→recall by
  meaning→Claude answers grounded in memory. Embeddings = Gemini (free, 1536-dim), brain = Claude. See `supabase/functions/README.md`.
- **Voice assistant loop — WORKING on hardware.** Talk button → glasses mic (SCO) → Gemini STT (`transcribe`)
  → Claude RAG (`chat`) → Android TTS → glasses speaker. Verified: asked "Where did I park?" into the glasses,
  Jarvis spoke the memory-grounded answer back. Activation is push-to-talk (no wake word yet).
- **Phase 0 + camera (0D) + voice loop all done.** Next: Phase 2 Wi-Fi media transfer (pull captured photos),
  then polish (wake word, streaming STT/TTS, real auth UI, cloud Supabase).

## Layout
- `supabase/` — local backend (migrations, tests, config).
- `android/` — the app (multi-module Gradle).

## Prerequisites
- Docker Desktop (running)
- Supabase CLI (`scoop install supabase`) — built/verified on v2.98.1
- Node 24 (for Edge Functions later)

## Local stack (ports shifted +100 to coexist with another local project on the defaults)
| Service | URL |
|---|---|
| API / Project URL | http://127.0.0.1:54421 |
| Studio (DB GUI) | http://127.0.0.1:54423 |
| Mailpit (email testing) | http://127.0.0.1:54424 |
| Database | postgresql://postgres:postgres@127.0.0.1:54422/postgres |

Local dev keys (shared Supabase defaults — **not secret, never use in prod**):
- Publishable: `sb_publishable_ACJWlzQHlZjBrEguHvfOxg_3BJgxAaH`
- Secret: `sb_secret_N7UND0UgjKTVK-Uodkm0Hg_xSvEMPvz`

## Commands (always pass `--workdir` or run from this folder)
```powershell
$w = "C:\Users\admin\Documents\APP\jarvis"
supabase start  --workdir $w      # bring the stack up
supabase status --workdir $w      # show URLs/keys
supabase stop   --workdir $w      # stop containers (data persists)
supabase db reset --workdir $w    # rebuild DB from migrations (drops local data)
supabase db lint --workdir $w     # schema lint
supabase db advisors --local --workdir $w   # security/perf advisors
# run a SQL file against the local db:
Get-Content supabase\tests\verify_memory_schema.sql | docker exec -i supabase_db_jarvis psql -U postgres -d postgres
```

## Schema (see `supabase/migrations/20260610214254_init_memory_schema.sql`)
- `public.memories(id, user_id, created_at, type, text, embedding vector(1536), media_path, lat, lng, tags, metadata)`
  - **RLS**: each user sees/edits only their own rows (4 policies).
  - **HNSW** index on `embedding` (cosine).
  - **embedding dim = 1536** — MUST match the app's embedding model; change here before any real data if we pick Voyage (1024).
- `public.match_memories(query_embedding, match_count, match_threshold, filter_type)` →
  the caller's memories ranked by cosine similarity (SECURITY INVOKER, RLS-enforced). RAG recall.
- Verification script: `supabase/tests/verify_memory_schema.sql` (proves structure + recall ordering through RLS).

## Migrating to cloud later
`supabase link --project-ref <ref>` then `supabase db push` replays the same migrations to the cloud project. The schema is migration-based specifically so this is a clean lift.
