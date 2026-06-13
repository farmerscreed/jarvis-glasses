-- Agent Delegation M4 (Phase 2 groundwork): the task store for delegated "deliberate lane" work.
-- One row per delegated Claude Code task (research/coding/email/calendar). Lets tickets survive,
-- be reviewable in the app, and (later) drive an FCM "done" notification. Phase 1 (M1–M3) runs
-- synchronously and doesn't need this; M4 async + hosted does. Deployed to LOCAL first; prod is a
-- separate director-authorized deploy. See docs/AGENT_DELEGATION.md §2 / §10.

create table if not exists public.agent_tasks (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users (id) on delete cascade,
  capability  text not null default 'research', -- research | coding | email | calendar | other
  prompt      text not null,                    -- what the director asked for
  status      text not null default 'queued',   -- queued | running | done | error | timeout
  summary     text not null default '',         -- short spoken-style summary of the outcome
  result      text not null default '',         -- full result text
  error       text,                             -- failure reason when status = error/timeout
  env         text not null default 'local',    -- which environment ran it (home PC, hosted VM, …)
  bridge_id   text,                             -- the bridge's task id (correlation/audit)
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create index if not exists agent_tasks_user_created_idx
  on public.agent_tasks (user_id, created_at desc);

alter table public.agent_tasks enable row level security;

-- A user can only see/manage their own delegated tasks.
create policy "agent_tasks_owner_all" on public.agent_tasks
  for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);
