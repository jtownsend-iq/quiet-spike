-- supabase/migrations/0001_capture_items.sql
-- Postgres mirror of schema/capture.sql. Adds user_id + RLS for the
-- per-user PowerSync filter. Apply via the Supabase dashboard SQL editor
-- or `supabase db push`. See TOOLCHAIN.md for the manual setup steps.
--
-- Per ADR-002: capture_items append-only, no conflicts. PowerSync sync
-- rules (powersync/sync_rules.yaml) enforce per-user filtering at the
-- replication layer; RLS here enforces it at the database layer.

create extension if not exists "pgcrypto";

create table if not exists public.capture_items (
    id           text          primary key,
    user_id      uuid          not null references auth.users (id) on delete cascade,
    raw_text     text          not null,
    captured_at  timestamptz   not null,
    device_id    text          not null,
    client_seq   bigint        not null,
    source       text          not null
        check (source in ('sheet','voice','email','plugin','hotkey','drag','post_call')),
    inserted_at  timestamptz   not null default now()
);

create unique index if not exists ux_capture_items_device_seq
    on public.capture_items (user_id, device_id, client_seq);

create index if not exists ix_capture_items_user_captured_at
    on public.capture_items (user_id, captured_at desc);

alter table public.capture_items enable row level security;

-- Read: user sees only their own rows.
create policy capture_items_select_own
    on public.capture_items
    for select
    using (auth.uid() = user_id);

-- Insert: user can only insert rows tagged with their own uid.
-- Append-only by mandate (ADR-002): no UPDATE / DELETE policies are created.
create policy capture_items_insert_own
    on public.capture_items
    for insert
    with check (auth.uid() = user_id);
