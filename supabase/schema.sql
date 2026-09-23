-- DashboardTasks: Supabase schema
create extension if not exists pgcrypto;

create table if not exists public.tasks (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  title text not null check (char_length(trim(title)) > 0),
  description text,
  category text not null default 'Inbox',
  priority text not null default 'normal' check (priority in ('niedrig','normal','hoch')),
  due_at timestamptz,
  completed boolean not null default false,
  completed_at timestamptz,
  source text not null default 'text' check (source in ('text','voice')),
  waiting_for boolean not null default false,
  recurrence text not null default 'none',
  reminder_at timestamptz,
  reminded_at timestamptz,
  next_recurrence_created boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- Bestehende Installationen sicher erweitern:
alter table public.tasks add column if not exists waiting_for boolean not null default false;
alter table public.tasks add column if not exists recurrence text not null default 'none';
alter table public.tasks add column if not exists reminder_at timestamptz;
alter table public.tasks add column if not exists reminded_at timestamptz;
alter table public.tasks add column if not exists next_recurrence_created boolean not null default false;

alter table public.tasks enable row level security;

drop policy if exists "Eigene Aufgaben lesen" on public.tasks;
create policy "Eigene Aufgaben lesen" on public.tasks
for select to authenticated
using ((select auth.uid()) = user_id);

drop policy if exists "Eigene Aufgaben anlegen" on public.tasks;
create policy "Eigene Aufgaben anlegen" on public.tasks
for insert to authenticated
with check ((select auth.uid()) = user_id);

drop policy if exists "Eigene Aufgaben ändern" on public.tasks;
create policy "Eigene Aufgaben ändern" on public.tasks
for update to authenticated
using (auth.uid() = user_id)
with check ((select auth.uid()) = user_id);

drop policy if exists "Eigene Aufgaben löschen" on public.tasks;
create policy "Eigene Aufgaben löschen" on public.tasks
for delete to authenticated
using ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.tasks to authenticated;

create or replace function public.set_updated_at()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
begin
  new.updated_at = now();
  if new.completed = true and old.completed = false then
    new.completed_at = now();
  elsif new.completed = false then
    new.completed_at = null;
  end if;
  return new;
end;
$$;

drop trigger if exists tasks_set_updated_at on public.tasks;
create trigger tasks_set_updated_at
before update on public.tasks
for each row execute function public.set_updated_at();

create index if not exists tasks_user_due_idx on public.tasks(user_id, due_at);
create index if not exists tasks_user_completed_idx on public.tasks(user_id, completed);
create index if not exists tasks_user_waiting_idx on public.tasks(user_id, waiting_for);
create index if not exists tasks_user_reminder_idx on public.tasks(user_id, reminder_at);

create table if not exists public.push_subscriptions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  endpoint text not null,
  p256dh text not null,
  auth text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, endpoint)
);

alter table public.push_subscriptions enable row level security;

drop policy if exists "Eigene Push Abos lesen" on public.push_subscriptions;
create policy "Eigene Push Abos lesen" on public.push_subscriptions
for select to authenticated
using ((select auth.uid()) = user_id);

drop policy if exists "Eigene Push Abos anlegen" on public.push_subscriptions;
create policy "Eigene Push Abos anlegen" on public.push_subscriptions
for insert to authenticated
with check ((select auth.uid()) = user_id);

drop policy if exists "Eigene Push Abos ändern" on public.push_subscriptions;
create policy "Eigene Push Abos ändern" on public.push_subscriptions
for update to authenticated
using (auth.uid() = user_id)
with check ((select auth.uid()) = user_id);

drop policy if exists "Eigene Push Abos löschen" on public.push_subscriptions;
create policy "Eigene Push Abos löschen" on public.push_subscriptions
for delete to authenticated
using ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.push_subscriptions to authenticated;

create index if not exists push_subscriptions_user_idx
on public.push_subscriptions(user_id);


create table if not exists public.push_server_config (
  id boolean primary key default true check (id),
  vapid_public_key text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.push_server_config enable row level security;
revoke all on public.push_server_config from anon, authenticated;

drop policy if exists "Push Server Config Backend" on public.push_server_config;
create policy "Push Server Config Backend" on public.push_server_config
for all to service_role
using (true)
with check (true);

create or replace function public.configure_vapid(
  p_private_key text,
  p_public_key text
)
returns void
language plpgsql
security definer
set search_path = public, vault, pg_catalog
as $$
declare
  sid uuid;
begin
  select id into sid
  from vault.secrets
  where name = 'dashboardtasks_vapid_private'
  limit 1;

  if sid is null then
    perform vault.create_secret(
      p_private_key,
      'dashboardtasks_vapid_private',
      'DashboardTasks Web Push VAPID private key'
    );
  else
    perform vault.update_secret(
      sid,
      p_private_key,
      'dashboardtasks_vapid_private',
      'DashboardTasks Web Push VAPID private key'
    );
  end if;

  insert into public.push_server_config(id, vapid_public_key, updated_at)
  values (true, p_public_key, now())
  on conflict (id) do update
    set vapid_public_key = excluded.vapid_public_key,
        updated_at = now();
end;
$$;

revoke all on function public.configure_vapid(text,text) from public, anon, authenticated;
grant execute on function public.configure_vapid(text,text) to service_role;

create or replace function public.get_vapid_private()
returns text
language sql
security definer
set search_path = vault, pg_catalog
as $$
  select decrypted_secret
  from vault.decrypted_secrets
  where name = 'dashboardtasks_vapid_private'
  limit 1
$$;

revoke all on function public.get_vapid_private() from public, anon, authenticated;
grant execute on function public.get_vapid_private() to service_role;
