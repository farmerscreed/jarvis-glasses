-- Phase 2 · M3/M4 hardening (2026-10-14): every 5 minutes, ask the `meeting` function to restart any
-- transcription or summary that got stuck, failed, or never started (see the "sweep" op).
-- The shared secret lives in Vault as `meeting_internal_secret` (created by hand, never in this repo) and as
-- the function secret MEETING_INTERNAL_SECRET.

create extension if not exists pg_cron;
create extension if not exists pg_net;

select cron.unschedule('meeting-sweep') where exists (select 1 from cron.job where jobname = 'meeting-sweep');

select cron.schedule(
  'meeting-sweep',
  '*/5 * * * *',
  $$
  select net.http_post(
    url     := 'https://agtuimnppqbrjocuzqsk.supabase.co/functions/v1/meeting',
    headers := jsonb_build_object(
      'content-type', 'application/json',
      'x-meeting-secret', (select decrypted_secret from vault.decrypted_secrets where name = 'meeting_internal_secret')
    ),
    body    := '{"op":"sweep"}'::jsonb,
    timeout_milliseconds := 60000
  );
  $$
);
