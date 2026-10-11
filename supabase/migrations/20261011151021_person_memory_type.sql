-- People memory (Phase 1, 2026-10-11): "Jarvis, remember this person: Mr Ade, supplier, met at the
-- Trans-Amadi yard." A person is an ordinary memory (embedded, searchable, offline-first) with
-- type 'person' and structured fields in metadata (name, role, organisation, where, phone, email).
-- No face recognition — an optional photo is only kept for the user to look at.
alter table public.memories drop constraint if exists memories_type_check;
alter table public.memories add constraint memories_type_check
  check (type in ('note','voice_note','meeting','photo','qa','ocr','journal','person'));
