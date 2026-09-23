-- Run after the Edge Function "send-reminders" is deployed.
create extension if not exists pg_cron;
create extension if not exists pg_net;

select cron.unschedule(jobid)
from cron.job
where jobname = 'dashboardtasks-push-reminders';

select cron.schedule(
  'dashboardtasks-push-reminders',
  '* * * * *',
  $$
  select net.http_post(
    url := 'https://hfpryzswevnpmqdaidzj.supabase.co/functions/v1/send-reminders',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'apikey', 'sb_publishable_odMpT_m3G4RihPHoaYFMKA_fz7NPVsy'
    ),
    body := jsonb_build_object('scheduled_at', now()),
    timeout_milliseconds := 10000
  ) as request_id;
  $$
);
