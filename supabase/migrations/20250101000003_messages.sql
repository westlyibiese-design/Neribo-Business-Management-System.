create table public.messages (
  id uuid primary key default gen_random_uuid(),
  business_id uuid not null references public.businesses(id) on delete cascade,
  name text not null, email text not null, phone text, subject text,
  message text not null,
  status text not null default 'new' check (status in ('new','read')),
  reply_status text not null default 'none' check (reply_status in ('none','pending','replied')),
  source text not null default 'website_contact_form',
  is_deleted boolean not null default false,
  created_at timestamptz not null default now(),
  read_at timestamptz, replied_at timestamptz
);
create index messages_biz_created_idx on public.messages (business_id, created_at desc);
create index messages_biz_status_idx on public.messages (business_id, status) where is_deleted = false;
alter table public.messages enable row level security;

-- Table-level privileges (RLS policies below still decide which rows).
grant insert on public.messages to anon;
grant select, update on public.messages to authenticated;

-- Anonymous visitors may only INSERT, and only for an existing business, never read.
-- The business check uses a security-definer helper because anon cannot read public.businesses.
create or replace function public.business_exists(p_business_id uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.businesses b where b.id = p_business_id) $$;
revoke all on function public.business_exists(uuid) from public;
grant execute on function public.business_exists(uuid) to anon, authenticated, service_role;

create policy "anon can submit messages" on public.messages for insert to anon
  with check (public.business_exists(business_id) and status = 'new' and reply_status = 'none' and is_deleted = false);
-- Staff who may use the inbox (Westly: super_admin, manager, receptionist) read/update their own business only.
create policy "inbox staff read" on public.messages for select to authenticated
  using (business_id = public.current_business_id() and public.current_member_role() in ('super_admin','manager','receptionist'));
create policy "inbox staff update" on public.messages for update to authenticated
  using (business_id = public.current_business_id() and public.current_member_role() in ('super_admin','manager','receptionist'))
  with check (business_id = public.current_business_id());
alter publication supabase_realtime add table public.messages;
