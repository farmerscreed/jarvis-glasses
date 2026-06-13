-- Assistant memory v1.1: the always-on curated profile (Hermes/OpenClaw pattern).
-- One row per user holding JARVIS's character (soul) + curated durable facts about the user
-- (user_facts). These are injected into the chat system prompt on every turn, so JARVIS knows
-- the director + itself in all answers — distinct from the firehose of episodic `memories`.

create table if not exists public.profile (
  user_id    uuid primary key references auth.users (id) on delete cascade,
  soul       text not null default '',  -- JARVIS's character / operating charter
  user_facts text not null default '',  -- curated, distilled facts about the user
  updated_at timestamptz not null default now()
);

alter table public.profile enable row level security;

-- A user can only read/write their own profile row.
create policy "profile_owner_all" on public.profile
  for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);
