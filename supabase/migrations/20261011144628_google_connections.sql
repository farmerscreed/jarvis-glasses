-- Phase 1 (2026-10-11): email + calendar in the cloud.
--
-- google_connections — one row per user who connected Google (Gmail drafts, Calendar, Contacts).
-- Holds the Google REFRESH TOKEN, encrypted by the Edge Functions (AES-GCM, key in the
-- GOOGLE_TOKEN_KEY secret) before it is stored. The app never reads this table: it goes through the
-- `google-connect` function (status / connect / disconnect). Only the service role touches it.
--
-- pending_actions — an outward action JARVIS proposed and is waiting for the user to confirm
-- ("Add 'Site meeting' Tue 10:00?" → yes). The cloud only performs an action it proposed itself,
-- once, within 10 minutes — the confirm gate is enforced server-side, not just in the app.

create table if not exists public.google_connections (
  user_id           uuid primary key references auth.users (id) on delete cascade,
  google_email      text not null default '',
  refresh_token_enc text not null,                  -- base64(iv || AES-GCM ciphertext)
  scopes            text[] not null default '{}',
  status            text not null default 'ok',     -- ok | needs_reconnect
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);

alter table public.google_connections enable row level security;
-- No policies on purpose: no client access at all (service role bypasses RLS).
revoke all on public.google_connections from anon, authenticated;

create table if not exists public.pending_actions (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users (id) on delete cascade,
  kind        text not null,                        -- calendar_add
  payload     jsonb not null,
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null default now() + interval '10 minutes',
  used_at     timestamptz
);

create index if not exists pending_actions_user_created_idx
  on public.pending_actions (user_id, created_at desc);

alter table public.pending_actions enable row level security;
revoke all on public.pending_actions from anon, authenticated;
