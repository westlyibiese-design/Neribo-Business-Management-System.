-- Phase 24: private key used by the 5-minute housekeeping schedule to prove its identity to the Edge Function.
-- The "private" schema is not exposed through the API. RLS is on and there are NO policies.
create schema if not exists private;

create table if not exists private.cron_keys (
  name text primary key,
  key  text not null
);

alter table private.cron_keys enable row level security;

revoke all on schema private from anon, authenticated;
revoke all on all tables in schema private from anon, authenticated;

-- One row only, created once. Re-running this file never changes an existing key.
insert into private.cron_keys (name, key)
values ('housekeeping', gen_random_uuid()::text)
on conflict (name) do nothing;

-- The Edge Function reads the key through this one function (the "private" schema is not reachable through the API).
-- Only the service role may run it; anon and signed-in users cannot.
create or replace function public.get_cron_key(p_name text)
returns text
language sql
security definer
stable
set search_path = ''
as $$
  select key from private.cron_keys where name = p_name;
$$;

revoke all on function public.get_cron_key(text) from public, anon, authenticated;
grant execute on function public.get_cron_key(text) to service_role;
