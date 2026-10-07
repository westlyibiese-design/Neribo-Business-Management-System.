create table public.device_registrations (
  id uuid primary key default gen_random_uuid(),
  business_id uuid not null references public.businesses(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  device_id text not null,              -- random UUID the app generates once and stores in encrypted prefs
  device_label text not null,
  user_agent text,
  created_at timestamptz not null default now(),
  last_used_at timestamptz,
  is_active boolean not null default true,
  revoked_at timestamptz,
  revoked_by uuid,
  unique (user_id, device_id)
);
create table public.device_pins (
  id uuid primary key default gen_random_uuid(),
  business_id uuid not null references public.businesses(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  registration_id uuid not null unique references public.device_registrations(id) on delete cascade,
  pin_hash text not null,               -- salted PBKDF2, never plaintext
  failed_attempts int not null default 0,
  locked_until timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create table public.device_auth_events (
  id uuid primary key default gen_random_uuid(),
  business_id uuid references public.businesses(id) on delete cascade,
  user_id uuid not null,
  device_id text,
  event_type text not null,
  method text,
  detail text,
  created_at timestamptz not null default now()
);
create index device_registrations_user_idx on public.device_registrations (user_id) where is_active = true;
create index device_pins_user_idx on public.device_pins (user_id);
create index device_auth_events_user_idx on public.device_auth_events (user_id, created_at desc);
alter table public.device_registrations enable row level security;
alter table public.device_pins enable row level security;
alter table public.device_auth_events enable row level security;
-- Deliberately NO policies: only the service-role key (inside Edge Functions) can touch these tables.
