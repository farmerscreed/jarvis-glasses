-- Phase 2 · M5 + M6 (2026-10-14): recordings library state + audio retention.
--  * action_done: which action items (by index in summary.action_items) Law has ticked off.
--  * profile.audio_keep_days: how long meeting AUDIO is kept in the cloud (0 = forever). Text is kept forever.
--  * Daily pg_cron job → the `meeting` function's internal "retention" op deletes expired, unstarred audio
--    (storage objects) and stamps recordings.audio_deleted_at; transcript, summary and memory stay.

alter table public.recordings
  add column if not exists action_done jsonb not null default '[]'::jsonb;

alter table public.profile
  add column if not exists audio_keep_days int not null default 90
    check (audio_keep_days in (0, 30, 90, 365));

create index if not exists recordings_audio_expiry_idx on public.recordings (audio_expires_at)
  where audio_deleted_at is null and starred = false;

select cron.unschedule('meeting-retention') where exists (select 1 from cron.job where jobname = 'meeting-retention');

select cron.schedule(
  'meeting-retention',
  '15 3 * * *',   -- 03:15 UTC daily (04:15 in Lagos)
  $$
  select net.http_post(
    url     := 'https://agtuimnppqbrjocuzqsk.supabase.co/functions/v1/meeting',
    headers := jsonb_build_object(
      'content-type', 'application/json',
      'x-meeting-secret', (select decrypted_secret from vault.decrypted_secrets where name = 'meeting_internal_secret')
    ),
    body    := '{"op":"retention"}'::jsonb,
    timeout_milliseconds := 60000
  );
  $$
);
