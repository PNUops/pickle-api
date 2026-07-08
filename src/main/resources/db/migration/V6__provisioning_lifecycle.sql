-- Provisioning & lifecycle schema (M3 WP-B1): provisioning_tasks (user-visible
-- task state), vm_events (permanent per-VM history) and the vms lifecycle
-- columns (one-shot initial credentials, scheduled deletion).
-- See docs/plan/02-data-model.md and docs/plan/03-provisioning.md.

create type provisioning_task_kind as enum ('PROVISION', 'DELETE', 'REINSTALL');
create type provisioning_task_status as enum
    ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'RETRYING', 'NEEDS_ADMIN');
create type vm_event_type as enum
    ('CREATE', 'START', 'STOP', 'REBOOT', 'FORCE_STOP', 'DELETE', 'EMERGENCY_DELETE', 'REINSTALL',
     'SCHEDULE_DELETE', 'CANCEL_SCHEDULED_DELETE');
create type vm_delete_kind as enum ('SELF', 'ADMIN', 'EMERGENCY');

create table provisioning_tasks (
    id             bigint generated always as identity primary key,
    vm_id          bigint not null references vms (id),
    kind           provisioning_task_kind not null,
    current_step   int not null default 0,
    status         provisioning_task_status not null default 'PENDING',
    attempts       int not null default 0,
    last_error     text,
    jobrunr_job_id text,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);

-- Duplicate-approve / duplicate-job guard: at most one live task per
-- (vm, kind); finished tasks (DONE/FAILED) stay as history and do not block.
create unique index provisioning_tasks_vm_kind_active_uq
    on provisioning_tasks (vm_id, kind)
 where status in ('PENDING', 'RUNNING', 'RETRYING', 'NEEDS_ADMIN');

create index provisioning_tasks_vm_id_idx on provisioning_tasks (vm_id);

create table vm_events (
    id         bigint generated always as identity primary key,
    vm_id      bigint not null references vms (id),
    type       vm_event_type not null,
    -- FK is fine here, unlike audit_logs: user rows are kept permanently even
    -- after withdrawal (operator decision 2026-07-08), so the target never
    -- disappears, and events are written inside the acting transaction.
    actor_id   bigint references users (id),
    detail     text,
    created_at timestamptz not null default now()
);

create index vm_events_vm_id_created_at_idx on vm_events (vm_id, created_at);

alter table vms
    add column initial_password           text,
    add column initial_password_hash      text,
    add column initial_password_viewed_at timestamptz,
    add column delete_kind                vm_delete_kind,
    add column delete_scheduled_for       timestamptz,
    add column delete_requested_by        bigint references users (id),
    add column delete_reason              text;

comment on column vms.initial_password is
    'Plaintext by design, but only until first view: the one-shot password endpoint returns it once and nulls this column (docs/plan/03 initial credentials). Only the BCrypt hash in initial_password_hash is kept for support verification.';
comment on column vms.delete_scheduled_for is
    'When the deletion sweeper may hard-delete: self-delete now()+vm_delete_grace_hours, admin delete >= admin_delete_min_notice_days out.';

-- ── Settings keys read by the M3 deletion flow. Operator-tunable at runtime.
--    168 h = 7 days grace (operator decision 2026-07-08, docs/plan/03). ──
insert into settings (key, value, description) values
    ('vm_delete_grace_hours', '168'::jsonb,
     'Grace period between a self-delete request and the hard delete (docs/plan/03).'),
    ('admin_delete_min_notice_days', '7'::jsonb,
     'Minimum notice an admin-scheduled routine delete must give the owner (docs/plan/03).');
