-- DashboardTasks push scheduler.
-- The invocation secret is read from Supabase Vault and never stored in source control.

create extension if not exists pg_cron;
create extension if not exists pg_net;

select cron.unschedule(jobid)
from cron.job
where jobname='dashboardtasks-push-reminders';

select cron.schedule(
  'dashboardtasks-push-reminders',
  '* * * * *',
  $job$
    select net.http_post(
      url := 'https://hfpryzswevnpmqdaidzj.supabase.co/functions/v1/send-reminders',
      headers := jsonb_build_object(
        'Content-Type','application/json',
        'x-cron-secret',
        (select decrypted_secret
         from vault.decrypted_secrets
         where name='dashboardtasks_cron_secret'
         limit 1)
      ),
      body := jsonb_build_object('scheduled_at', now()),
      timeout_milliseconds := 10000
    );
  $job$
);

-- The cron job runs as postgres; browser roles do not need these schemas.
revoke all on schema net from public, anon, authenticated;
revoke all on schema cron from public, anon, authenticated;
revoke all on all functions in schema net from public, anon, authenticated;
revoke all on all tables in schema net from public, anon, authenticated;
revoke all on all sequences in schema net from public, anon, authenticated;
revoke all on all functions in schema cron from public, anon, authenticated;
revoke all on all tables in schema cron from public, anon, authenticated;
revoke all on all sequences in schema cron from public, anon, authenticated;
