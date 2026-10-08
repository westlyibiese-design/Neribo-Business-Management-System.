-- Phase 10A: one row per phone (FCM token) that may receive push messages.
-- RLS is on and there are NO policies, so only the service role (Edge Functions) can read or write it.
create table if not exists public.push_tokens (
  id uuid primary key default gen_random_uuid(),
  business_id uuid not null references public.businesses(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  token text not null unique,
  device_id text,
  platform text not null default 'android',
  created_at timestamptz default now(),
  updated_at timestamptz default now()
);
create index if not exists push_tokens_user_idx on public.push_tokens (user_id);
create index if not exists push_tokens_business_idx on public.push_tokens (business_id);
alter table public.push_tokens enable row level security;
-- Deliberately NO policies.
