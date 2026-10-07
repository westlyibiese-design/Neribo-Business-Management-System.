-- Tiny table used by the create-business function to slow down abuse (max 5 new businesses per IP per hour).
-- RLS is on and there are no policies, so only the service role (Edge Functions) can read or write it.
create table if not exists public.signup_attempts (
  id bigint generated always as identity primary key,
  ip text not null,
  created_at timestamptz not null default now()
);
create index if not exists signup_attempts_ip_time_idx on public.signup_attempts (ip, created_at desc);
alter table public.signup_attempts enable row level security;
