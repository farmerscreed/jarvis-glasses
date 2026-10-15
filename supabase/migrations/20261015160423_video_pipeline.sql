-- Phase 3 · V2/V3 (2026-10-15): videos go through the recordings pipeline (docs/PHASE3_VIDEO_DESIGN.md §3).
--  * The phone uploads a small analysis copy in ≤ 3-min pieces (`NNN.mp4`, 480p, ~0.7 Mbps ≈ 16 MB) to the same
--    private `recordings` bucket → allow video/mp4 and raise the per-object limit.
--  * Gemini "watches" each piece: what was SAID → recording_segments (as for meetings), what was SEEN → also
--    recording_segments with speaker 'Seen', and KEY MOMENTS → recording_chunks.moments (chunk-relative seconds).
--  * The phone grabs full-quality photos from the original at those moments → recordings.frames
--    [{t, path (media bucket), why}].
--  * memories.type gains 'video' (the searchable memory of a video / site walk).

update storage.buckets
   set file_size_limit = 104857600,  -- 100 MiB
       allowed_mime_types = array['audio/mp4', 'audio/m4a', 'audio/aac', 'video/mp4']
 where id = 'recordings';

alter table public.recording_chunks add column if not exists moments jsonb not null default '[]'::jsonb;
alter table public.recordings add column if not exists frames jsonb not null default '[]'::jsonb;

alter table public.memories drop constraint if exists memories_type_check;
alter table public.memories add constraint memories_type_check
  check (type in ('note','voice_note','meeting','photo','qa','ocr','journal','person','video'));
