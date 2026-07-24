-- Account-lifecycle columns + status-change history.
-- users.withdrawn_at already exists (V2); this adds the admin-disable pair and
-- a queryable history table backing the admin user-detail view. audit_logs
-- still records every transition — user_status_changes exists so the detail
-- screen can render "who/why/when" without scanning the audit stream, and so
-- enable can restore the pre-disable status (contract: enable = restore, never
-- an email-verification bypass).

alter table users
    add column disabled_at     timestamptz,
    add column disabled_reason text;

comment on column users.disabled_at is
    'When an admin disabled this account (null unless status = DISABLED).';
comment on column users.disabled_reason is
    'Operator-entered reason for the current disable. Cleared together with disabled_at on enable — past reasons live in user_status_changes.';

create table user_status_changes (
    id          bigint generated always as identity primary key,
    user_id     bigint not null references users (id),
    from_status user_status not null,
    to_status   user_status not null,
    actor_id    bigint references users (id),
    reason      text,
    changed_at  timestamptz not null default now()
);

create index user_status_changes_user_id_idx on user_status_changes (user_id);

comment on table user_status_changes is
    'Account status transition history: admin disable/enable and self-withdrawal. Enable restores from_status of the matching disable row.';
comment on column user_status_changes.actor_id is
    'Who performed the transition (the user themself for withdrawal; null only for future system-driven transitions).';
