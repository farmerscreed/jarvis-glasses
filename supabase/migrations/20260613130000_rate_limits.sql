-- Per-user request caps for the Edge Functions, so a runaway client loop can't burn the AI budget.
-- A fixed-window counter per (user, function); check_rate_limit atomically rolls the window and
-- increments, returning whether the call is allowed. SECURITY DEFINER so the table stays private.

create table if not exists public.rate_limits (
  user_id      uuid        not null references auth.users (id) on delete cascade,
  fn           text        not null,
  window_start timestamptz not null default now(),
  count        int         not null default 0,
  primary key (user_id, fn)
);

alter table public.rate_limits enable row level security;
-- No policies: only the SECURITY DEFINER function below touches this table; clients can't read/write it.

create or replace function public.check_rate_limit(
  p_fn text,
  p_limit int,
  p_window_seconds int
)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  v_user  uuid := auth.uid();
  v_now   timestamptz := now();
  v_count int;
begin
  if v_user is null then
    return false; -- unauthenticated callers are never allowed
  end if;

  insert into public.rate_limits (user_id, fn, window_start, count)
    values (v_user, p_fn, v_now, 1)
  on conflict (user_id, fn) do update set
    -- roll the window if the old one has expired, else keep counting in it
    window_start = case
      when public.rate_limits.window_start < v_now - make_interval(secs => p_window_seconds)
      then v_now else public.rate_limits.window_start end,
    count = case
      when public.rate_limits.window_start < v_now - make_interval(secs => p_window_seconds)
      then 1 else public.rate_limits.count + 1 end
  returning count into v_count;

  return v_count <= p_limit;
end;
$$;

grant execute on function public.check_rate_limit(text, int, int) to authenticated;
