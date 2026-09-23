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
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.tasks enable row level security;

drop policy if exists "Eigene Aufgaben lesen" on public.tasks;
create policy "Eigene Aufgaben lesen" on public.tasks
for select to authenticated
using (auth.uid() = user_id);

drop policy if exists "Eigene Aufgaben anlegen" on public.tasks;
create policy "Eigene Aufgaben anlegen" on public.tasks
for insert to authenticated
with check (auth.uid() = user_id);

drop policy if exists "Eigene Aufgaben ändern" on public.tasks;
create policy "Eigene Aufgaben ändern" on public.tasks
for update to authenticated
using (auth.uid() = user_id)
with check (auth.uid() = user_id);

drop policy if exists "Eigene Aufgaben löschen" on public.tasks;
create policy "Eigene Aufgaben löschen" on public.tasks
for delete to authenticated
using (auth.uid() = user_id);

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
