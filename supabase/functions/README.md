# Edge Functions

The AI layer. All provider keys live here (server-side), never in the app.

| Function | Method · Body | Does |
|---|---|---|
| `ingest` | POST `{ text, type?, media_path?, tags?, metadata?, lat?, lng? }` | Embed `text` → insert a memory for the signed-in user (RLS). |
| `recall` | POST `{ query, limit?, threshold?, type? }` | Embed `query` → `match_memories` RPC → ranked memories. |
| `chat`   | POST `{ message, history?, recallLimit? }` | RAG: embed → recall → **Claude** answers grounded in memories → persist the Q&A. Returns `{ answer, memories_used }`. |
| `transcribe` | POST `{ audioBase64, mimeType? }` | **Gemini** multimodal STT (model `gemini-2.5-flash`, retry + fallback to `gemini-2.5-flash-lite`). Returns `{ text }`. |

| `meeting` | POST `{ op, client_id?, … }` | **Phase 2 meetings** — chunk upload check, finish, library ops (list/get/audio-urls/star/title/speaker/tick/delete-audio/ask/settings/costs), delete. Background ops (header `x-meeting-secret`): work-chunk, work-summary, work-drive, sweep (pg_cron 5 min), retention (pg_cron daily). **Deploy with `--no-verify-jwt`.** Op list at the top of `meeting/index.ts`; record in `docs/SESSION_LOG_2026-10-14.md`. |

(Other functions — research, vision, translate, calendar, email, google-connect, person, push, profile, distill,
remember, chat-stream — are described at the top of their own `index.ts`.)

Shared helpers in `_shared/`: `http.ts` (CORS/JSON), `supabaseClient.ts` (user-scoped client so RLS applies), `embeddings.ts` (Gemini, 1536-dim), `anthropic.ts` (Claude Messages API), `llm.ts` (**model switchboard**: `llmJson(job…)` over the `model_routes` table, logs `llm_calls`), `meetingStt.ts` (Gemini meeting transcription), `meetingSummary.ts` (meeting notes + ask), `drive.ts` (Google Drive), `google.ts` (Google connection), `fcm.ts` (push).

**New functions can't be created from the CLI on this project (HTTP 500, 2026-10-12)** — create a placeholder in the
dashboard first, then `supabase functions deploy <name> --use-api`.

## Providers (defaults, overridable in `.env`)
- **Embeddings**: Google **Gemini `gemini-embedding-001`** at 1536 dims (free tier). `GEMINI_API_KEY`, `EMBED_PROVIDER=gemini`.
  (OpenAI `text-embedding-3-small` kept as a fallback: set `EMBED_PROVIDER=openai` + `OPENAI_API_KEY`.)
- **Chat/vision**: Claude `claude-sonnet-4-6`. `ANTHROPIC_API_KEY`.
- `SUPABASE_URL` / `SUPABASE_ANON_KEY` are injected automatically — do not set them.

## Run locally
1. Put your keys in `supabase/functions/.env` (gitignored):
   ```
   OPENAI_API_KEY=sk-...
   ANTHROPIC_API_KEY=sk-ant-...
   ```
2. Serve (env is read at start — restart after editing `.env`):
   ```powershell
   supabase functions serve --env-file supabase/functions/.env --workdir C:\Users\admin\Documents\APP\jarvis
   ```
3. End-to-end test (signs up a local user, then ingest → recall → chat):
   ```powershell
   pwsh supabase/tests/test_functions.ps1   # or: powershell -File supabase\tests\test_functions.ps1
   ```

## Status
**VERIFIED end-to-end (2026-06-10).** `test_functions.ps1` passes: ingest 3 memories → recall
"where did I leave the car?" returns the parking note at 0.71 similarity (top) → chat "what did Sam
say about the budget?" → Claude answers grounded in the recalled memory. Embeddings = Gemini, brain = Claude.
