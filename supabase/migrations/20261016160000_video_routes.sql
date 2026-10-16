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
