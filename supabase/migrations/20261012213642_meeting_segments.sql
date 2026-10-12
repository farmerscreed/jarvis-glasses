-- Phase 2 · M3 (2026-10-12): the speaker-labelled transcript of each meeting chunk.
-- Written only by the `meeting` Edge Function (service role); the app may read its own lines.
-- Times are seconds FROM THE START OF THAT CHUNK (the chunk's place in the meeting comes from
-- recording_chunks.duration_s of the earlier chunks). Speaker labels ("Speaker 1"…) are per chunk until
-- M4 reconciles them across the meeting into recordings.speakers.

create table if not exists public.recording_segments (
  recording_id uuid not null references public.recordings (id) on delete cascade,
  seq          int not null,
  idx          int not null,
  user_id      uuid not null references auth.users (id) on delete cascade,
  t_start_s    real not null default 0,
  t_end_s      real not null default 0,
  speaker      text not null default '',
  text         text not null default '',
  primary key (recording_id, seq, idx)
);
create index if not exists recording_segments_user_idx on public.recording_segments (user_id);

alter table public.recording_segments enable row level security;
revoke all on public.recording_segments from anon, authenticated;
grant select on public.recording_segments to authenticated;
create policy "recording_segments_select_own" on public.recording_segments
  for select to authenticated using ( (select auth.uid()) = user_id );

-- When each chunk's transcription started/finished, and which model did it (cost + speed tracking).
alter table public.recording_chunks
  add column if not exists model text,
  add column if not exists transcribed_at timestamptz,
  add column if not exists started_at timestamptz;
