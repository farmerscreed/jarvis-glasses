# Edge Functions

The AI layer. All provider keys live here (server-side), never in the app.

| Function | Method · Body | Does |
|---|---|---|
| `ingest` | POST `{ text, type?, media_path?, tags?, metadata?, lat?, lng? }` | Embed `text` → insert a memory for the signed-in user (RLS). |
| `recall` | POST `{ query, limit?, threshold?, type? }` | Embed `query` → `match_memories` RPC → ranked memories. |
| `chat`   | POST `{ message, history?, recallLimit? }` | RAG: embed → recall → **Claude** answers grounded in memories → persist the Q&A. Returns `{ answer, memories_used }`. |
| `transcribe` | POST `{ audioBase64, mimeType? }` | **Gemini** multimodal STT (model `gemini-2.5-flash`, retry + fallback to `gemini-2.5-flash-lite`). Returns `{ text }`. |

Shared helpers in `_shared/`: `http.ts` (CORS/JSON), `supabaseClient.ts` (user-scoped client so RLS applies), `embeddings.ts` (OpenAI, 1536-dim), `anthropic.ts` (Claude Messages API).

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
