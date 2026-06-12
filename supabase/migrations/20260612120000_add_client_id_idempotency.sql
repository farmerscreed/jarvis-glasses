-- Offline-first (Phase C): client-generated idempotency key.
-- The app writes every memory locally first (Room) and drains an outbox to the cloud when
-- connectivity allows. Retries are inevitable (flaky networks, app restarts mid-upload), so the
-- server must dedupe: a memory carries a client-generated UUID, and re-sending the same one
-- returns the existing row instead of creating a duplicate.
alter table public.memories
  add column if not exists client_id uuid;

-- Partial unique index: many rows may have a NULL client_id (legacy / server-only inserts), but
-- any non-null client_id is unique. This is what makes the ingest upsert idempotent.
create unique index if not exists memories_client_id_key
  on public.memories (client_id)
  where client_id is not null;

comment on column public.memories.client_id is
  'Client-generated UUID for offline-first idempotent sync; null for server-originated rows.';
