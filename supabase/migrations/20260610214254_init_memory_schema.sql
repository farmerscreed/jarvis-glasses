-- Memory backend for the JARVIS/ECHO AI companion (AIMB-G2 smart glasses).
-- The "Personal Memory Index": every meaningful event is embedded and stored for
-- semantic recall (retrieval-augmented generation). One index powers all features.

-- pgvector lives in the `extensions` schema on Supabase.
create extension if not exists vector with schema extensions;

-- ----------------------------------------------------------------------------
-- memories: the single index behind second-brain, meeting capture, look-&-ask, OCR.
-- embedding dimension 1536 (OpenAI text-embedding-3-small and many others). This MUST
-- match the embedding model the app uses; trivially changeable before any real data.
-- ----------------------------------------------------------------------------
create table public.memories (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users (id) on delete cascade,
  created_at  timestamptz not null default now(),
  type        text not null default 'note'
              check (type in ('note','voice_note','meeting','photo','qa','ocr','journal')),
  text        text,                                   -- transcript / answer / OCR text
  embedding   extensions.vector(1536),                -- semantic fingerprint (nullable until embedded)
  media_path  text,                                   -- Storage object path (image/audio), nullable
  lat         double precision,                       -- optional capture location (PostGIS can come later)
  lng         double precision,
  tags        text[] not null default '{}',
  metadata    jsonb  not null default '{}'::jsonb
);

comment on table public.memories is
  'Personal Memory Index for the AI companion; one row per remembered event (voice note, meeting, photo, Q&A, OCR).';

-- Per-user recency and type filters.
create index memories_user_created_idx on public.memories (user_id, created_at desc);
create index memories_user_type_idx    on public.memories (user_id, type);

-- Approximate nearest-neighbour search over embeddings (cosine distance).
create index memories_embedding_hnsw_idx
  on public.memories using hnsw (embedding extensions.vector_cosine_ops);

-- ----------------------------------------------------------------------------
-- Row Level Security: a user can only ever see or modify their own memories.
-- ----------------------------------------------------------------------------
alter table public.memories enable row level security;

create policy "memories_select_own" on public.memories
  for select to authenticated
  using ( (select auth.uid()) = user_id );

create policy "memories_insert_own" on public.memories
  for insert to authenticated
  with check ( (select auth.uid()) = user_id );

create policy "memories_update_own" on public.memories
  for update to authenticated
  using ( (select auth.uid()) = user_id )
  with check ( (select auth.uid()) = user_id );

create policy "memories_delete_own" on public.memories
  for delete to authenticated
  using ( (select auth.uid()) = user_id );

-- ----------------------------------------------------------------------------
-- match_memories: semantic recall (RAG). Returns the caller's memories ranked by
-- cosine similarity to a query embedding. SECURITY INVOKER => RLS is enforced.
-- The explicit operator(extensions.<=>) keeps cosine distance resolvable under the
-- empty search_path and lets the HNSW (vector_cosine_ops) index serve the ORDER BY.
-- ----------------------------------------------------------------------------
create or replace function public.match_memories (
  query_embedding extensions.vector(1536),
  match_count     int              default 10,
  match_threshold double precision default 0.0,
  filter_type     text             default null
)
returns table (
  id          uuid,
  created_at  timestamptz,
  type        text,
  text        text,
  media_path  text,
  tags        text[],
  metadata    jsonb,
  similarity  double precision
)
language sql
stable
security invoker
set search_path = ''
as $$
  select
    m.id, m.created_at, m.type, m.text, m.media_path, m.tags, m.metadata,
    1 - (m.embedding operator(extensions.<=>) query_embedding) as similarity
  from public.memories m
  where m.user_id = (select auth.uid())
    and m.embedding is not null
    and (filter_type is null or m.type = filter_type)
    and 1 - (m.embedding operator(extensions.<=>) query_embedding) >= match_threshold
  order by m.embedding operator(extensions.<=>) query_embedding
  limit greatest(match_count, 1);
$$;
