-- Notifications & announcements (contract v0.5.0).
-- Expand-only: new enums/tables/indexes plus settings seeds; no existing
-- object is altered structurally.

create type notification_channel as enum ('EMAIL');
create type notification_status as enum ('PENDING', 'SENT', 'FAILED', 'SKIPPED');

-- ── announcements: one row per admin broadcast; recipients get individual
--    notifications rows (fan-out snapshot at send time). ──
create table announcements (
    id              bigint generated always as identity primary key,
    author_id       bigint not null references users (id),
    scope           text not null check (scope in ('ALL', 'ORG', 'GROUP')),
    org_id          bigint references orgs (id),
    group_id        bigint references groups (id),
    title           text not null,
    body            text not null,
    recipient_count int not null default 0,
    created_at      timestamptz not null default now(),
    -- scope pins its target columns: ALL = broadcast (no target), ORG = org
    -- only, GROUP = group only (the sender org is not a column — visibility
    -- follows the author's org via users.org_id).
    constraint announcements_scope_target_chk check (
        (scope = 'ALL'   and org_id is null     and group_id is null) or
        (scope = 'ORG'   and org_id is not null and group_id is null) or
        (scope = 'GROUP' and group_id is not null and org_id is null)
    )
);
create index announcements_org_id_idx on announcements (org_id);

-- ── notifications: the per-user inbox row IS the email delivery log
--    (single table, channel EMAIL in v1). Titles/bodies are
--    rendered Korean at publish time; payload carries whitelisted display
--    fields only — never tokens or passwords. ──
create table notifications (
    id              bigint generated always as identity primary key,
    user_id         bigint not null references users (id),
    event           text not null,              -- dot-namespaced catalog id
    title           text not null,
    body            text not null,
    link_path       text,                       -- console-relative, nullable
    importance      text not null default 'NORMAL' check (importance in ('NORMAL', 'HIGH')),
    payload         jsonb,
    dedup_key       text,                       -- per-user idempotency guard
    announcement_id bigint references announcements (id),
    channel         notification_channel not null default 'EMAIL',
    status          notification_status not null default 'PENDING',
    attempts        int not null default 0,
    last_error      text,
    next_attempt_at timestamptz not null default now(),
    sent_at         timestamptz,
    read_at         timestamptz,
    created_at      timestamptz not null default now()
);

create index notifications_user_created_idx on notifications (user_id, created_at desc);
create index notifications_user_unread_idx on notifications (user_id) where read_at is null;
create index notifications_pending_due_idx on notifications (next_attempt_at) where status = 'PENDING';
create index notifications_status_created_idx on notifications (status, created_at);
create unique index notifications_user_dedup_uq on notifications (user_id, dedup_key)
    where dedup_key is not null;

-- No settings data here any more. This migration seeded notification_retention_days
-- and vm_expiry_notice_days, and relabelled nine earlier settings descriptions to
-- Korean because the admin settings screen renders `description` verbatim
-- (contract v0.5.0). All of it was deployment content rather than schema: an
-- operator bootstrap script writes the rows now, with their Korean text written
-- once at creation, and the dev/test seeder supplies equivalents. The relabels
-- went with them -- they targeted rows no migration creates, so they matched
-- nothing.
