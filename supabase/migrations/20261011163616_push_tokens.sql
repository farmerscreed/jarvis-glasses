-- Push notifications (FCM), 2026-10-11. One row per installed app (an FCM registration token).
-- Written and read only by the `push` Edge Function (service role) — the app registers through it,
-- so there are no client grants. A token is unique per device install; when Google says a token is
-- dead (UNREGISTERED), the sender deletes it.
create table if not exists public.push_tokens (
  token       text primary key,
  user_id     uuid not null references auth.users (id) on delete cascade,
  platform    text not null default 'android',
  app_id      text not null default '',              -- com.echo.companion / .dev
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create index if not exists push_tokens_user_idx on public.push_tokens (user_id);

alter table public.push_tokens enable row level security;
revoke all on public.push_tokens from anon, authenticated;
