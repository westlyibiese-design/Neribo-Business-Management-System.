-- Records every shared-device PIN sign-in attempt so verify-pin can slow down guessing.
-- Limits (enforced by the verify-pin function): 5 failed tries per connection and 30 per business, per 10 minutes.
-- RLS is on and there are no policies, so only the service role (Edge Functions) can read or write it.
create table if not exists public.pin_login_attempts (
  id uuid primary key default gen_random_uuid(),
  business_id uuid references public.businesses(id) on delete cascade,
  ip text,
  success boolean not null,
  created_at timestamptz not null default now()
);
create index if not exists pin_login_attempts_business_time_idx
  on public.pin_login_attempts (business_id, created_at desc);
alter table public.pin_login_attempts enable row level security;
