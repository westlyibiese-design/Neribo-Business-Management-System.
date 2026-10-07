create extension if not exists "pgcrypto";

-- Allowed role keys (NO developer role exists in NBMS)
create domain public.nbms_role as text check (value in (
 'super_admin','manager','receptionist','accountant','staff','waiter','housekeeping',
 'bar_attendant','laundry_valet','operations_manager','maintenance_technician',
 'security_guard','driver','restaurant_attendant','kitchen_staff','gym_staff'));

create table public.businesses (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(name) between 2 and 120),
  code text not null unique check (code ~ '^[A-Z0-9]{6,8}$'),
  business_type text not null default 'hotel',
  currency text not null default 'NGN',
  currency_symbol text not null default '₦',
  timezone text not null default 'Africa/Lagos',
  maintenance_mode boolean not null default false,
  maintenance_message text,
  owner_user_id uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.business_roles (
  business_id uuid not null references public.businesses(id) on delete cascade,
  role public.nbms_role not null,
  enabled boolean not null default true,
  primary key (business_id, role)
);

create table public.business_members (
  user_id uuid primary key references auth.users(id) on delete cascade,
  business_id uuid not null references public.businesses(id) on delete cascade,
  role public.nbms_role not null,
  name text not null,
  email text,
  phone text,
  status text not null default 'active' check (status in ('active','suspended')),
  uses_pin boolean not null default false,
  pin_lookup text,                      -- HMAC-SHA256(pepper, businessId||pin), hex. Unique per business.
  pin_hash text,                        -- PBKDF2 hash string (set by Edge Functions)
  pin_failed_attempts int not null default 0,
  pin_locked_until timestamptz,
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint pin_lookup_unique unique (business_id, pin_lookup)
);
create index business_members_business_idx on public.business_members (business_id, role);

-- touch updated_at
create or replace function public.touch_updated_at() returns trigger language plpgsql as $$
begin new.updated_at = now(); return new; end $$;
create trigger trg_businesses_touch before update on public.businesses for each row execute function public.touch_updated_at();
create trigger trg_members_touch before update on public.business_members for each row execute function public.touch_updated_at();

-- Who is calling? (security definer so RLS policies can use it without recursion)
create or replace function public.current_business_id() returns uuid
language sql stable security definer set search_path = public as $$
  select business_id from public.business_members where user_id = auth.uid() and status = 'active' limit 1 $$;
create or replace function public.current_member_role() returns text
language sql stable security definer set search_path = public as $$
  select role::text from public.business_members where user_id = auth.uid() and status = 'active' limit 1 $$;
revoke all on function public.current_business_id() from public;
revoke all on function public.current_member_role() from public;
grant execute on function public.current_business_id() to authenticated, service_role;
grant execute on function public.current_member_role() to authenticated, service_role;

-- Short, unambiguous business code for PIN login (no 0/O/1/I)
create or replace function public.generate_business_code() returns text language plpgsql as $$
declare alphabet text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; v_code text; i int;
begin
  loop
    v_code := '';
    for i in 1..6 loop v_code := v_code || substr(alphabet, 1 + floor(random()*length(alphabet))::int, 1); end loop;
    exit when not exists (select 1 from public.businesses b where b.code = v_code);
  end loop;
  return v_code;
end $$;
revoke all on function public.generate_business_code() from public;
grant execute on function public.generate_business_code() to service_role;

alter table public.businesses enable row level security;
alter table public.business_roles enable row level security;
alter table public.business_members enable row level security;

-- Staff can read their own business; ALL writes go through Edge Functions (service role).
create policy "members read own business" on public.businesses for select to authenticated using (id = public.current_business_id());
create policy "members read own business roles" on public.business_roles for select to authenticated using (business_id = public.current_business_id());
create policy "members read colleagues" on public.business_members for select to authenticated using (business_id = public.current_business_id());
-- also let a user read their own row even if suspended (so the app can show "account suspended")
create policy "member reads self" on public.business_members for select to authenticated using (user_id = auth.uid());

-- Hide PIN secrets from the client entirely (column-level privileges)
revoke select on public.business_members from authenticated, anon;
grant select (user_id, business_id, role, name, email, phone, status, uses_pin, created_by, created_at, updated_at)
  on public.business_members to authenticated;
