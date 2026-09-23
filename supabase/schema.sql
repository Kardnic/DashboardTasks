-- DashboardTasks: hardened Supabase schema
-- Public clients use only the publishable key. No server secret belongs in this repository.

create extension if not exists pgcrypto;

create table if not exists public.tasks (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  title text not null check (char_length(trim(title)) > 0 and char_length(title) <= 240),
  description text check (description is null or char_length(description) <= 1000),
  category text not null default 'Inbox'
    check (category in ('Inbox','Arbeit','Privat','JS Wenau','Projekte','Sonstiges')),
  priority text not null default 'normal'
    check (priority in ('niedrig','normal','hoch')),
  due_at timestamptz,
  completed boolean not null default false,
  completed_at timestamptz,
  source text not null default 'text'
    check (source in ('text','voice')),
  waiting_for boolean not null default false,
  recurrence text not null default 'none'
    check (recurrence in ('none','daily','weekly','monthly')),
  reminder_at timestamptz,
  reminded_at timestamptz,
  next_recurrence_created boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- Safe upgrades for older installations.
alter table public.tasks add column if not exists waiting_for boolean not null default false;
alter table public.tasks add column if not exists recurrence text not null default 'none';
alter table public.tasks add column if not exists reminder_at timestamptz;
alter table public.tasks add column if not exists reminded_at timestamptz;
alter table public.tasks add column if not exists next_recurrence_created boolean not null default false;

do $$
begin
  if not exists (select 1 from pg_constraint where conname='tasks_category_allowed') then
    alter table public.tasks add constraint tasks_category_allowed
      check (category in ('Inbox','Arbeit','Privat','JS Wenau','Projekte','Sonstiges')) not valid;
    alter table public.tasks validate constraint tasks_category_allowed;
  end if;
  if not exists (select 1 from pg_constraint where conname='tasks_recurrence_allowed') then
    alter table public.tasks add constraint tasks_recurrence_allowed
      check (recurrence in ('none','daily','weekly','monthly')) not valid;
    alter table public.tasks validate constraint tasks_recurrence_allowed;
  end if;
  if not exists (select 1 from pg_constraint where conname='tasks_title_max_length') then
    alter table public.tasks add constraint tasks_title_max_length
      check (char_length(title) <= 240) not valid;
    alter table public.tasks validate constraint tasks_title_max_length;
  end if;
  if not exists (select 1 from pg_constraint where conname='tasks_description_max_length') then
    alter table public.tasks add constraint tasks_description_max_length
      check (description is null or char_length(description) <= 1000) not valid;
    alter table public.tasks validate constraint tasks_description_max_length;
  end if;
end $$;

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

do $$
begin
  if not exists (select 1 from pg_constraint where conname='push_endpoint_sane') then
    alter table public.push_subscriptions add constraint push_endpoint_sane
      check (char_length(endpoint) between 10 and 2048 and endpoint like 'https://%') not valid;
    alter table public.push_subscriptions validate constraint push_endpoint_sane;
  end if;
  if not exists (select 1 from pg_constraint where conname='push_p256dh_sane') then
    alter table public.push_subscriptions add constraint push_p256dh_sane
      check (char_length(p256dh) between 20 and 256) not valid;
    alter table public.push_subscriptions validate constraint push_p256dh_sane;
  end if;
  if not exists (select 1 from pg_constraint where conname='push_auth_sane') then
    alter table public.push_subscriptions add constraint push_auth_sane
      check (char_length(auth) between 8 and 128) not valid;
    alter table public.push_subscriptions validate constraint push_auth_sane;
  end if;
end $$;

-- Private allowlist: intentionally does NOT auto-add future auth users.
create schema if not exists app_private;
revoke all on schema app_private from public, anon, authenticated;

create table if not exists app_private.allowed_users (
  user_id uuid primary key references auth.users(id) on delete cascade,
  allowed_since timestamptz not null default now()
);

create or replace function app_private.is_allowed_user()
returns boolean
language sql
stable
security definer
set search_path = pg_catalog
as $$
  select exists (
    select 1
    from app_private.allowed_users au
    where au.user_id = (select auth.uid())
  )
$$;

revoke all on function app_private.is_allowed_user() from public, anon;
grant usage on schema app_private to authenticated;
grant execute on function app_private.is_allowed_user() to authenticated;

alter table public.tasks enable row level security;
alter table public.push_subscriptions enable row level security;

drop policy if exists "Eigene Aufgaben lesen" on public.tasks;
create policy "Eigene Aufgaben lesen" on public.tasks
for select to authenticated
using (app_private.is_allowed_user() and (select auth.uid()) = user_id);

drop policy if exists "Eigene Aufgaben anlegen" on public.tasks;
create policy "Eigene Aufgaben anlegen" on public.tasks
for insert to authenticated
with check (app_private.is_allowed_user() and (select auth.uid()) = user_id);

drop policy if exists "Eigene Aufgaben ändern" on public.tasks;
create policy "Eigene Aufgaben ändern" on public.tasks
for update to authenticated
using (app_private.is_allowed_user() and (select auth.uid()) = user_id)
with check (app_private.is_allowed_user() and (select auth.uid()) = user_id);

drop policy if exists "Eigene Aufgaben löschen" on public.tasks;
create policy "Eigene Aufgaben löschen" on public.tasks
for delete to authenticated
using (app_private.is_allowed_user() and (select auth.uid()) = user_id);

drop policy if exists "Eigene Push Abos lesen" on public.push_subscriptions;
create policy "Eigene Push Abos lesen" on public.push_subscriptions
for select to authenticated
using (app_private.is_allowed_user() and (select auth.uid()) = user_id);

drop policy if exists "Eigene Push Abos anlegen" on public.push_subscriptions;
create policy "Eigene Push Abos anlegen" on public.push_subscriptions
for insert to authenticated
with check (app_private.is_allowed_user() and (select auth.uid()) = user_id);

drop policy if exists "Eigene Push Abos ändern" on public.push_subscriptions;
create policy "Eigene Push Abos ändern" on public.push_subscriptions
for update to authenticated
using (app_private.is_allowed_user() and (select auth.uid()) = user_id)
with check (app_private.is_allowed_user() and (select auth.uid()) = user_id);

drop policy if exists "Eigene Push Abos löschen" on public.push_subscriptions;
create policy "Eigene Push Abos löschen" on public.push_subscriptions
for delete to authenticated
using (app_private.is_allowed_user() and (select auth.uid()) = user_id);

-- Least privilege: anonymous users get no Data API table access.
revoke all on table public.tasks from anon, authenticated;
revoke all on table public.push_subscriptions from anon, authenticated;

grant select, delete on table public.tasks to authenticated;
grant insert (
  title, description, category, priority, due_at, source,
  waiting_for, recurrence, reminder_at
) on table public.tasks to authenticated;
grant update (
  title, description, category, priority, due_at, completed, source,
  waiting_for, recurrence, reminder_at, reminded_at, next_recurrence_created
) on table public.tasks to authenticated;

grant select, delete on table public.push_subscriptions to authenticated;
grant insert (endpoint, p256dh, auth, updated_at)
  on table public.push_subscriptions to authenticated;
grant update (p256dh, auth, updated_at)
  on table public.push_subscriptions to authenticated;

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

revoke all on function public.set_updated_at() from public, anon, authenticated;

drop trigger if exists tasks_set_updated_at on public.tasks;
create trigger tasks_set_updated_at
before update on public.tasks
for each row execute function public.set_updated_at();

create index if not exists tasks_user_due_idx on public.tasks(user_id, due_at);
create index if not exists tasks_user_completed_idx on public.tasks(user_id, completed);
create index if not exists tasks_user_waiting_idx on public.tasks(user_id, waiting_for);
create index if not exists tasks_user_reminder_idx on public.tasks(user_id, reminder_at);
create index if not exists push_subscriptions_user_idx on public.push_subscriptions(user_id);

-- Backend-only Vault accessors.
create or replace function public.get_vapid_private()
returns text
language sql
security definer
set search_path = vault, pg_catalog
as $$
  select decrypted_secret
  from vault.decrypted_secrets
  where name='dashboardtasks_vapid_private'
  limit 1
$$;

revoke all on function public.get_vapid_private() from public, anon, authenticated;
grant execute on function public.get_vapid_private() to service_role;

do $$
declare sid uuid;
begin
  select id into sid
  from vault.secrets
  where name='dashboardtasks_cron_secret'
  limit 1;

  if sid is null then
    perform vault.create_secret(
      encode(gen_random_bytes(32),'hex'),
      'dashboardtasks_cron_secret',
      'Secret used only by pg_cron to invoke DashboardTasks reminder function'
    );
  end if;
end $$;

create or replace function public.get_cron_secret()
returns text
language sql
security definer
set search_path = vault, pg_catalog
as $$
  select decrypted_secret
  from vault.decrypted_secrets
  where name='dashboardtasks_cron_secret'
  limit 1
$$;

revoke all on function public.get_cron_secret() from public, anon, authenticated;
grant execute on function public.get_cron_secret() to service_role;

-- Secure defaults for future objects.
revoke create on schema public from public, anon, authenticated;
revoke usage on schema public from anon;
grant usage on schema public to authenticated;

alter default privileges for role postgres in schema public
  revoke all on tables from anon, authenticated;
alter default privileges for role postgres in schema public
  revoke all on sequences from anon, authenticated;
alter default privileges for role postgres in schema public
  revoke execute on functions from public, anon, authenticated;

-- IMPORTANT: add intended user IDs to app_private.allowed_users explicitly.
