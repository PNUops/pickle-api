create type gpu_status as enum ('ACTIVE', 'MAINTENANCE', 'RETIRED');
create type gpu_allocation_status as enum ('QUEUED', 'ALLOCATED', 'RELEASING', 'RELEASED', 'CANCELED');
create type gpu_connection_status as enum ('NONE', 'ATTACHING', 'ATTACHED', 'DETACHING', 'ERROR');
alter type vm_event_type add value if not exists 'GPU_ATTACH';
alter type vm_event_type add value if not exists 'GPU_DETACH';
alter type drift_finding_kind add value if not exists 'GPU_ATTACHMENT_MISMATCH';

create table gpus (
    id bigint generated always as identity primary key,
    public_id uuid not null unique default gen_random_uuid(),
    node_id bigint not null references nodes(id),
    mapping_name text not null,
    model text not null,
    vram_mb integer not null check (vram_mb > 0),
    hostpci_slot text not null default 'hostpci0' check (hostpci_slot ~ '^hostpci[0-9]+$'),
    status gpu_status not null default 'MAINTENANCE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (node_id, mapping_name),
    check (mapping_name ~ '^[A-Za-z0-9_-]+$')
);

create table gpu_request_details (
    request_id bigint primary key references requests(id),
    vm_id bigint references vms(id),
    lease_hours integer not null check (lease_hours > 0),
    granted_lease_hours integer check (granted_lease_hours > 0),
    granted_gpu_id bigint references gpus(id),
    granted_priority integer,
    check ((granted_lease_hours is null) = (granted_priority is null))
);

create table gpu_allocations (
    id bigint generated always as identity primary key,
    public_id uuid not null unique default gen_random_uuid(),
    request_id bigint not null unique references requests(id),
    workspace_id bigint not null references workspaces(id),
    org_id bigint not null references orgs(id),
    name text not null,
    gpu_id bigint references gpus(id),
    preferred_gpu_id bigint references gpus(id),
    vm_id bigint references vms(id),
    status gpu_allocation_status not null default 'QUEUED',
    connection_status gpu_connection_status not null default 'NONE',
    granted_lease_hours integer not null check (granted_lease_hours > 0),
    priority integer not null default 0,
    queued_at timestamptz not null default now(),
    granted_start_date date,
    granted_end_date date,
    allocated_at timestamptz,
    lease_ends_at timestamptz,
    unattached_since timestamptz,
    attached_at timestamptz,
    release_reason text check (release_reason in ('USER_RELEASE','LEASE_EXPIRED','ADMIN_RECLAIM','GRANT_ENDED')),
    operation_id uuid,
    error text,
    released_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (connection_status = 'NONE' or vm_id is not null),
    check (status not in ('RELEASED','CANCELED') or connection_status = 'NONE'),
    check (status not in ('ALLOCATED','RELEASING') or
        (gpu_id is not null and allocated_at is not null and lease_ends_at is not null))
);
create unique index gpu_one_holder on gpu_allocations(gpu_id)
    where status in ('ALLOCATED','RELEASING');
create unique index gpu_one_connection_per_vm on gpu_allocations(vm_id)
    where connection_status <> 'NONE';
create index gpu_queue_order on gpu_allocations(priority desc, queued_at, id) where status = 'QUEUED';
create index gpu_allocation_workspace on gpu_allocations(workspace_id, created_at desc, id desc);

create table gpu_operations (
    id uuid primary key,
    allocation_id bigint not null references gpu_allocations(id),
    vm_id bigint not null references vms(id),
    node_id bigint not null references nodes(id),
    kind text not null check (kind in ('ATTACH','DETACH','RELEASE','VM_DELETE')),
    phase text not null default 'PENDING' check (phase in ('PENDING','RUNNING','DONE','ERROR')),
    step text not null default 'CLAIMED',
    was_running boolean not null,
    worker_id uuid,
    task_upid text,
    remote_dispatch_pending boolean not null default false,
    delete_requested_at timestamptz,
    delete_scheduled_for timestamptz,
    delete_kind text,
    actor_id bigint references users(id),
    admin_action boolean not null default false,
    error text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
alter table gpu_allocations add constraint gpu_allocation_operation_fk foreign key (operation_id) references gpu_operations(id);
alter table vms add column power_operation_id uuid;
create unique index gpu_one_live_operation on gpu_operations(allocation_id) where phase in ('PENDING','RUNNING');

create table gpu_utilization_samples (
    id bigint generated always as identity primary key,
    allocation_id bigint not null references gpu_allocations(id),
    gpu_id bigint not null references gpus(id),
    sampled_at timestamptz not null,
    util_percent double precision not null check (util_percent >= 0 and util_percent <= 100),
    mem_used_mb integer check (mem_used_mb >= 0),
    source text not null,
    confidence integer not null check (confidence >= 0 and confidence <= 100),
    raw jsonb,
    unique (allocation_id, sampled_at, source)
);
create index gpu_samples_allocation_time on gpu_utilization_samples(allocation_id, sampled_at);
create table gpu_reclaim_reviews (
    id bigint generated always as identity primary key,
    public_id uuid not null unique default gen_random_uuid(),
    allocation_id bigint not null references gpu_allocations(id),
    reason text not null check (reason in ('UNATTACHED','LOW_UTILIZATION')),
    evidence jsonb not null,
    decision text check (decision in ('KEEP','RECLAIM')),
    decision_reason text,
    decided_by bigint references users(id),
    decided_at timestamptz,
    snoozed_until timestamptz,
    created_at timestamptz not null default now()
);
create unique index gpu_one_open_review on gpu_reclaim_reviews(allocation_id) where decision is null;

create or replace function assert_gpu_request_is_granted() returns trigger language plpgsql as $$
declare rid bigint;
begin
    rid := coalesce(new.request_id, old.request_id);
    if exists (select 1 from requests r join request_reviews rv on rv.request_id = r.id
        left join gpu_request_details d on d.request_id = r.id
        where r.id = rid and r.resource_type = 'GPU' and rv.decision = 'APPROVE'
        and (d.granted_lease_hours is null or d.granted_priority is null)) then
        raise exception 'approved GPU request % has no granted specification', rid;
    end if;
    return null;
end $$;
create constraint trigger gpu_review_grant_check after insert or update on request_reviews
    deferrable initially deferred for each row execute function assert_gpu_request_is_granted();
create constraint trigger gpu_detail_grant_check after insert or update or delete on gpu_request_details
    deferrable initially deferred for each row execute function assert_gpu_request_is_granted();

-- Preserve every existing resource branch while adding the new grant requirement.
do $$
declare definition text;
begin
    definition := pg_get_functiondef('assert_approved_request_is_granted()'::regprocedure);
    if strpos(definition, 'else true') = 0 then
        raise exception 'approved-request guard changed; review its GPU branch before migrating';
    end if;
    definition := replace(definition, 'else true',
        'when ''GPU'' then not exists (select 1 from gpu_request_details gd where gd.request_id = rv.request_id and gd.granted_lease_hours is not null and gd.granted_priority is not null) else true');
    execute definition;
end $$;

create or replace function assert_gpu_detail_matches_decision() returns trigger language plpgsql as $$
declare rid bigint;
begin
    rid := coalesce(new.request_id, old.request_id);
    if exists (select 1 from gpu_request_details d where d.request_id = rid
        and (d.granted_lease_hours is not null or d.granted_gpu_id is not null or d.granted_priority is not null)
        and not exists (select 1 from request_reviews rv where rv.request_id = rid and rv.decision = 'APPROVE')) then
        raise exception 'GPU request % has a grant without an approval', rid;
    end if;
    return null;
end $$;
create constraint trigger gpu_detail_decision_check after insert or update or delete on gpu_request_details
    deferrable initially deferred for each row execute function assert_gpu_detail_matches_decision();
create constraint trigger gpu_review_detail_check after insert or update or delete on request_reviews
    deferrable initially deferred for each row execute function assert_gpu_detail_matches_decision();

-- Each dispatch is retained because a force-stop may supersede an earlier power task.
create table vm_power_dispatches (
    id uuid primary key,
    operation_id uuid not null,
    vm_id bigint not null references vms(id),
    task_upid text,
    dispatch_pending boolean not null default true,
    terminal boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index vm_power_dispatches_unfinished on vm_power_dispatches(vm_id,operation_id) where terminal = false;
