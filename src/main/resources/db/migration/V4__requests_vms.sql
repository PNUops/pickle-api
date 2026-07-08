-- VM requests, approval reviews and the M2 subset of vms (WP-B3).
-- See docs/plan/02-data-model.md. Rows are never physically deleted:
-- vm_requests keep their final status, vms use status/deleted_at columns.

create type vm_request_status as enum ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELED');
create type review_decision as enum ('APPROVE', 'REJECT');
create type vm_status as enum
    ('CREATING', 'RUNNING', 'STOPPED', 'REBOOTING', 'DELETING', 'DELETED', 'ERROR', 'NEEDS_ADMIN');

create table vm_requests (
    id                bigint generated always as identity primary key,
    group_id          bigint not null references groups (id),
    org_id            bigint not null references orgs (id),
    requester_id      bigint not null references users (id),
    purpose           text not null,
    course_or_project text,
    spec_reason       text,
    extra_note        text,
    template_id       bigint not null references vm_templates (id),
    req_vcpu          int not null,
    req_memory_mb     int not null,
    req_disk_gb       int not null,
    req_start_date    date,
    req_end_date      date,
    need_ssh          boolean not null,
    need_http         boolean not null,
    need_public       boolean not null,
    desired_subdomain text,
    root_domain       text,
    custom_domain     text,
    status            vm_request_status not null default 'SUBMITTED',
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

-- Admin queue: org-scoped for ORG_ADMIN, status-only for SYS_ADMIN.
create index vm_requests_org_id_status_idx on vm_requests (org_id, status);
create index vm_requests_status_idx on vm_requests (status);
create index vm_requests_group_id_idx on vm_requests (group_id);
create index vm_requests_requester_id_idx on vm_requests (requester_id);

create table vm_request_reviews (
    id                  bigint generated always as identity primary key,
    -- One review row per decision; a request is decided at most once.
    request_id          bigint not null unique references vm_requests (id),
    reviewer_id         bigint not null references users (id),
    decision            review_decision not null,
    comment             text,
    granted_vcpu        int,
    granted_memory_mb   int,
    granted_disk_gb     int,
    granted_template_id bigint references vm_templates (id),
    granted_start_date  date,
    granted_end_date    date,
    grant_ssh           boolean,
    grant_http          boolean,
    grant_public        boolean,
    -- null = auto placement (docs/plan/03 place step).
    node_id             bigint references nodes (id),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

create table vms (
    id               bigint generated always as identity primary key,
    -- Assigned at clone time by the real M3 pipeline; always null in M2.
    proxmox_vmid     int unique,
    node_id          bigint not null references nodes (id),
    group_id         bigint not null references groups (id),
    org_id           bigint not null references orgs (id),
    request_id       bigint not null references vm_requests (id),
    name             text not null,
    hostname         text not null unique,
    template_id      bigint not null references vm_templates (id),
    vcpu             int not null,
    memory_mb        int not null,
    disk_gb          int not null,
    -- Will reference ip_allocations(id); that table lands with the M3 IPAM
    -- migration, which also adds the FK (same pattern as nodes.ip_pool_id).
    ip_allocation_id bigint,
    ssh_username     text not null default 'student',
    start_date       date,
    end_date         date,
    status           vm_status not null default 'CREATING',
    status_detail    text,
    deleted_at       timestamptz,
    deleted_by       bigint references users (id),
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now()
);

create index vms_group_id_idx on vms (group_id);
create index vms_org_id_idx on vms (org_id);
create index vms_request_id_idx on vms (request_id);
create index vms_node_id_idx on vms (node_id);
