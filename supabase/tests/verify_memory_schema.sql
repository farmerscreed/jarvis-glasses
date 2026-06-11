-- End-to-end verification of the memory schema + pgvector recall.
-- Run: Get-Content this.sql | docker exec -i supabase_db_jarvis psql -U postgres -d postgres
set search_path = public, extensions;

\echo '== 1. structure checks =='
select 'vector_ext'      as check, count(*) from pg_extension where extname = 'vector';
select 'memories_table'  as check, count(*) from information_schema.tables where table_schema='public' and table_name='memories';
select 'match_fn'        as check, count(*) from pg_proc where proname = 'match_memories';
select 'rls_enabled'     as check, relrowsecurity::text from pg_class where oid = 'public.memories'::regclass;
select 'policies'        as check, count(*)::text from pg_policies where tablename = 'memories';
select 'hnsw_index'      as check, count(*)::text from pg_indexes where tablename='memories' and indexdef ilike '%hnsw%';

\echo '== 2. seed a test user + 3 memories (unit vectors at dims 1,2,3) =='
insert into auth.users (instance_id, id, aud, role, email, created_at, updated_at)
values ('00000000-0000-0000-0000-000000000000',
        '11111111-1111-1111-1111-111111111111',
        'authenticated','authenticated','tester@local', now(), now())
on conflict (id) do nothing;

delete from public.memories where user_id = '11111111-1111-1111-1111-111111111111';

insert into public.memories (user_id, type, text, embedding)
select '11111111-1111-1111-1111-111111111111', 'note', x.t,
       ('[' || (select string_agg(case when g = x.p then '1' else '0' end, ',')
                from generate_series(1,1536) g) || ']')::vector
from (values ('apple',1), ('banana',2), ('cherry',3)) as x(t, p);

select 'rows_inserted' as check, count(*)::text from public.memories
where user_id = '11111111-1111-1111-1111-111111111111';

\echo '== 3. direct similarity (query = unit vector @dim1 => apple should rank first ~1.0) =='
with q as (
  select ('[' || (select string_agg(case when g = 1 then '1' else '0' end, ',')
                  from generate_series(1,1536) g) || ']')::vector as e
)
select m.text, round((1 - (m.embedding <=> q.e))::numeric, 4) as similarity
from public.memories m, q
where m.user_id = '11111111-1111-1111-1111-111111111111'
order by m.embedding <=> q.e
limit 3;

\echo '== 4. match_memories through RLS as the authenticated user =='
begin;
  select set_config('role', 'authenticated', true);
  select set_config('request.jwt.claims',
    json_build_object('sub','11111111-1111-1111-1111-111111111111','role','authenticated')::text, true);
  select m.text, round(m.similarity::numeric, 4) as similarity
  from public.match_memories(
    ('[' || (select string_agg(case when g = 1 then '1' else '0' end, ',')
             from generate_series(1,1536) g) || ']')::vector,
    5, 0.0, null) m;
rollback;

\echo '== 5. cleanup =='
delete from public.memories where user_id = '11111111-1111-1111-1111-111111111111';
delete from auth.users where id = '11111111-1111-1111-1111-111111111111';
\echo 'DONE'
