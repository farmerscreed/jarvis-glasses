-- Storage for the Personal Memory Index: private buckets that back memories.media_path.
--   media  -> photos/videos synced off the glasses (Look & Ask, Second-Brain)
--   audio  -> voice clips / meeting recordings (Meeting Capture, voice notes)
-- Both are PRIVATE; the app reads objects with the signed-in user's JWT (RLS-enforced)
-- or via short-lived signed URLs. Object keys are namespaced by user id:
--   <user_id>/<filename>   so the first path segment identifies the owner.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values
  ('media', 'media', false, 52428800,  -- 50 MiB
     array['image/jpeg','image/png','image/webp','video/mp4']),
  ('audio', 'audio', false, 52428800,
     array['audio/wav','audio/mpeg','audio/mp4','audio/aac','audio/ogg'])
on conflict (id) do nothing;

-- ----------------------------------------------------------------------------
-- Row Level Security on storage.objects: a user may only touch objects whose
-- first path segment equals their own uid. Upsert needs INSERT + SELECT + UPDATE,
-- so all four verbs are granted (owner-scoped).
-- ----------------------------------------------------------------------------

create policy "media_select_own" on storage.objects
  for select to authenticated
  using ( bucket_id = 'media' and (storage.foldername(name))[1] = (select auth.uid())::text );

create policy "media_insert_own" on storage.objects
  for insert to authenticated
  with check ( bucket_id = 'media' and (storage.foldername(name))[1] = (select auth.uid())::text );

create policy "media_update_own" on storage.objects
  for update to authenticated
  using ( bucket_id = 'media' and (storage.foldername(name))[1] = (select auth.uid())::text )
  with check ( bucket_id = 'media' and (storage.foldername(name))[1] = (select auth.uid())::text );

create policy "media_delete_own" on storage.objects
  for delete to authenticated
  using ( bucket_id = 'media' and (storage.foldername(name))[1] = (select auth.uid())::text );

create policy "audio_select_own" on storage.objects
  for select to authenticated
  using ( bucket_id = 'audio' and (storage.foldername(name))[1] = (select auth.uid())::text );

create policy "audio_insert_own" on storage.objects
  for insert to authenticated
  with check ( bucket_id = 'audio' and (storage.foldername(name))[1] = (select auth.uid())::text );

create policy "audio_update_own" on storage.objects
  for update to authenticated
  using ( bucket_id = 'audio' and (storage.foldername(name))[1] = (select auth.uid())::text )
  with check ( bucket_id = 'audio' and (storage.foldername(name))[1] = (select auth.uid())::text );

create policy "audio_delete_own" on storage.objects
  for delete to authenticated
  using ( bucket_id = 'audio' and (storage.foldername(name))[1] = (select auth.uid())::text );
