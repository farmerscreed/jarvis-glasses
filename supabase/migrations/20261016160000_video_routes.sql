-- Phase 3 · V4/V6 (2026-10-16): model routes for the video jobs (they fell back to the 4k-token default; a long site
-- walk's report needs more). Shown in Settings → AI models & cost like the meeting jobs.
insert into public.model_routes (job, routes, note) values
  ('video_summary',
   '[{"provider":"anthropic","model":"default","max_tokens":4000},{"provider":"gemini","model":"gemini-2.5-flash","max_tokens":8000,"usd_in":0.3,"usd_out":2.5}]',
   'Video notes from what was seen + said (Gemini watches the video first).'),
  ('walk_report',
   '[{"provider":"anthropic","model":"default","max_tokens":8000},{"provider":"gemini","model":"gemini-2.5-flash","max_tokens":12000,"usd_in":0.3,"usd_out":2.5}]',
   'Site walk report: progress, problems with times, counts, safety, next steps.')
on conflict (job) do nothing;

-- 2026-10-17 (audit): watching video pieces had no row → it fell back to gemini-2.5-flash alone; give it the same
-- Flash-first chain as meeting transcripts (Flash → Flash-Lite) so a busy Flash doesn't fail the piece.
insert into public.model_routes (job, routes, note) values
  ('video_stt',
   '[{"provider":"gemini","model":"gemini-2.5-flash","usd_in":0.3,"usd_out":2.5},{"provider":"gemini","model":"gemini-2.5-flash-lite","usd_in":0.1,"usd_out":0.4}]',
   'Gemini watches each video piece: what was said, what was seen, key moments.')
on conflict (job) do nothing;
