-- Provisioning & lifecycle schema: provisioning_tasks (user-visible
-- task state), vm_events (permanent per-VM history) and the vms lifecycle
-- columns (one-shot initial credentials, scheduled deletion).

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
    'Plaintext by design, but only until first view: the one-shot password endpoint returns it once and nulls this column. Only the BCrypt hash in initial_password_hash is kept for support verification.';
comment on column vms.delete_scheduled_for is
    'When the deletion sweeper may hard-delete: self-delete now()+vm_delete_grace_hours, admin delete >= admin_delete_min_notice_days out.';

-- No settings seed here. The deletion flow reads vm_delete_grace_hours (and once
-- read admin_delete_min_notice_days, dropped in V54): both are operator policy
-- rather than schema. An operator bootstrap script writes them and the dev/test
-- seeder supplies an equivalent.
