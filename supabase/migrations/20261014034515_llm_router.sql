-- Phase 2 · M7 (2026-10-14): the model switchboard (_shared/llm.ts) + a log of every AI call.
--  * model_routes: per job, the models to try in order — editable with SQL, no app update or deploy needed.
--    Each entry: {provider: anthropic|openrouter|gemini, model, max_tokens?, usd_in?, usd_out?}
--    (usd_* = $ per million tokens, used for the cost estimate; "default" model = the CLAUDE_MODEL secret).
--    Entries whose provider has no key (e.g. openrouter before OPENROUTER_API_KEY is set) are skipped.
--  * llm_calls: one row per call (job, model, tokens, $ estimate, ms, ok/error) → Settings → AI models & cost.

create table if not exists public.model_routes (
  job        text primary key,
  routes     jsonb not null,
  note       text not null default '',
  updated_at timestamptz not null default now()
);
alter table public.model_routes enable row level security;
revoke all on public.model_routes from anon, authenticated;
grant select on public.model_routes to authenticated;
create policy "model_routes_read" on public.model_routes for select to authenticated using (true);

create table if not exists public.llm_calls (
  id         bigint generated always as identity primary key,
  user_id    uuid references auth.users (id) on delete cascade,
  job        text not null,
  provider   text not null,
  model      text not null,
  tokens_in  int not null default 0,
  tokens_out int not null default 0,
  usd_est    numeric(12, 6) not null default 0,
  ms         int not null default 0,
  ok         boolean not null default true,
  error      text,
  created_at timestamptz not null default now()
);
create index if not exists llm_calls_user_time_idx on public.llm_calls (user_id, created_at desc);
alter table public.llm_calls enable row level security;
revoke all on public.llm_calls from anon, authenticated;
grant select on public.llm_calls to authenticated;
create policy "llm_calls_select_own" on public.llm_calls
  for select to authenticated using ( (select auth.uid()) = user_id );

-- Starting routes = today's behaviour (Claude first). After OPENROUTER_API_KEY is set and the eval
-- (scripts/eval-meeting-summary.ts) picks a cheaper model, put an openrouter entry FIRST, e.g.:
--   update model_routes set routes = '[{"provider":"openrouter","model":"<picked model>","usd_in":0.3,"usd_out":1.2},
--     {"provider":"anthropic","model":"default"}]' where job = 'meeting_summary';
insert into public.model_routes (job, routes, note) values
  ('meeting_summary',
   '[{"provider":"anthropic","model":"default","max_tokens":4000},{"provider":"gemini","model":"gemini-2.5-flash","max_tokens":8000,"usd_in":0.3,"usd_out":2.5}]',
   'Meeting notes from the transcript. Accuracy of numbers/owners/dates is the bar.'),
  ('meeting_ask',
   '[{"provider":"anthropic","model":"default","max_tokens":1500},{"provider":"gemini","model":"gemini-2.5-flash","max_tokens":2000,"usd_in":0.3,"usd_out":2.5}]',
   'Answer a question about one meeting from its transcript.'),
  ('meeting_stt',
   '[{"provider":"gemini","model":"gemini-2.5-flash","usd_in":1.0,"usd_out":2.5},{"provider":"gemini","model":"gemini-2.5-flash-lite","usd_in":0.3,"usd_out":0.4}]',
   'Meeting chunk transcription (audio in). Logged for cost; the transcriber reads its models from here.')
on conflict (job) do nothing;
