-- Phase 24: run the housekeeping queue every 5 minutes for every business.
-- The key is read from private.cron_keys inside the database, so it never appears in code, chat or logs.
create extension if not exists pg_cron;
create extension if not exists pg_net;

-- Re-runnable: remove an older job with the same name first.
do $$
begin
  if exists (select 1 from cron.job where jobname = 'housekeeping-queue') then
    perform cron.unschedule('housekeeping-queue');
  end if;
end
$$;

select cron.schedule(
  'housekeeping-queue',
  '*/5 * * * *',
  $job$
  select net.http_post(
    url := 'https://cvqqnvfivzsgtxfcndko.supabase.co/functions/v1/housekeeping-queue-run',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'x-cron-key', (select key from private.cron_keys where name = 'housekeeping')
    ),
    body := '{}'::jsonb,
    timeout_milliseconds := 110000
  );
  $job$
);
