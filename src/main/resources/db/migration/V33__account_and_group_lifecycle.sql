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

-- Group soft delete (also used by account withdrawal). Group rows are
-- referenced by VM history and audit trails, so deletion keeps the row and
-- stamps deleted_at. The slug uniqueness moves to a partial index over live
-- rows so a new group can reuse a deleted group's slug (contract: deleteGroup).
-- Shared because account withdrawal soft-deletes the PERSONAL
-- group through the same column.

alter table groups
    add column deleted_at timestamptz,
    add column deleted_by bigint references users (id);

comment on column groups.deleted_at is
    'Soft-delete stamp. Deleted groups keep their row for VM/audit history but disappear from every list and lookup.';
comment on column groups.deleted_by is
    'Who deleted the group: the OWNER (deleteGroup) or the withdrawing user (PERSONAL group cleanup).';

alter table groups drop constraint groups_slug_key;
create unique index groups_slug_live_uniq on groups (slug) where deleted_at is null;
