-- Phase 2 · M2 (2026-10-12): meeting recordings in the cloud. See docs/PHASE2_DESIGN.md §3.
-- The phone records ~5-minute .m4a chunks and uploads each one to the private `recordings` bucket at
--   <user_id>/<client_id>/<seq>.m4a
-- then tells the `meeting` Edge Function, which checks size + sha256 and records it here.
-- Rows are written only by that function (service role); the app may READ its own rows (library UI).

create table if not exists public.recordings (
  id               uuid primary key default gen_random_uuid(),
  user_id          uuid not null references auth.users (id) on delete cascade,
  client_id        text not null,                       -- the phone's recording id (rec-<ms>-<rand>)
  kind             text not null default 'meeting',     -- meeting | voice_note | glasses_audio
  source           text not null default 'phone',       -- phone | glasses
  title            text not null default '',
  started_at       timestamptz not null,
  ended_at         timestamptz,
  duration_s       int not null default 0,
  status           text not null default 'recording',
     -- recording | uploading | uploaded | transcribing | summarising | ready | failed
  chunks_total     int,                                 -- known once stopped
  bookmarks        jsonb not null default '[]'::jsonb,  -- [{t_s, label}]
  speakers         jsonb not null default '{}'::jsonb,
  summary          jsonb,
  memory_id        uuid,
  audio_bytes      bigint not null default 0,
  starred          boolean not null default false,
  audio_expires_at timestamptz,
  audio_deleted_at timestamptz,
  error            text,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  unique (user_id, client_id)
);
create index if not exists recordings_user_started_idx on public.recordings (user_id, started_at desc);

create table if not exists public.recording_chunks (
  recording_id uuid not null references public.recordings (id) on delete cascade,
  seq          int not null,
  user_id      uuid not null references auth.users (id) on delete cascade,
  path         text not null,
  bytes        int not null,
  sha256       text not null,
  duration_s   int not null default 0,
  status       text not null default 'uploaded',   -- uploaded | transcribing | done | failed
  attempts     int not null default 0,
  error        text,
  created_at   timestamptz not null default now(),
  primary key (recording_id, seq)
);
create index if not exists recording_chunks_user_idx on public.recording_chunks (user_id);

alter table public.recordings enable row level security;
alter table public.recording_chunks enable row level security;
revoke all on public.recordings, public.recording_chunks from anon, authenticated;
grant select on public.recordings, public.recording_chunks to authenticated;

create policy "recordings_select_own" on public.recordings
  for select to authenticated using ( (select auth.uid()) = user_id );
create policy "recording_chunks_select_own" on public.recording_chunks
  for select to authenticated using ( (select auth.uid()) = user_id );

-- Private bucket; 20 MiB per object is plenty for a 5-minute chunk (~1.2 MB).
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('recordings', 'recordings', false, 20971520, array['audio/mp4', 'audio/m4a', 'audio/aac'])
on conflict (id) do nothing;

-- Owner-only objects (first path segment = uid). Upsert needs INSERT + SELECT + UPDATE.
create policy "recordings_obj_select_own" on storage.objects
  for select to authenticated
  using ( bucket_id = 'recordings' and (storage.foldername(name))[1] = (select auth.uid())::text );
create policy "recordings_obj_insert_own" on storage.objects
  for insert to authenticated
  with check ( bucket_id = 'recordings' and (storage.foldername(name))[1] = (select auth.uid())::text );
create policy "recordings_obj_update_own" on storage.objects
  for update to authenticated
  using ( bucket_id = 'recordings' and (storage.foldername(name))[1] = (select auth.uid())::text )
  with check ( bucket_id = 'recordings' and (storage.foldername(name))[1] = (select auth.uid())::text );
create policy "recordings_obj_delete_own" on storage.objects
  for delete to authenticated
  using ( bucket_id = 'recordings' and (storage.foldername(name))[1] = (select auth.uid())::text );
