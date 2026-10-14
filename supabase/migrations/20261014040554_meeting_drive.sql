-- Phase 2 · M6b (2026-10-14): starred meetings are copied to the owner's Google Drive
-- (Drive → JARVIS → Recordings → "<date> <title>": the audio pieces + notes.txt). Needs the Google connection
-- with the drive.file scope (only files JARVIS creates). drive_status: '' | copying | copied | not_connected | failed
alter table public.recordings
  add column if not exists drive_folder_id text,
  add column if not exists drive_status text not null default '',
  add column if not exists drive_copied_at timestamptz;
