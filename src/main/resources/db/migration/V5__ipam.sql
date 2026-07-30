-- IPAM: ip_pools / ip_allocations, the FKs deferred by V3
-- (nodes.ip_pool_id) and V4 (vms.ip_allocation_id), the vmbr2 student pool
-- seed and the quarantine setting.

create type allocation_status as enum ('ALLOCATED', 'RELEASED');

create table ip_pools (
    id              bigint generated always as identity primary key,
    name            text not null unique,
    cidr            cidr not null,
    gateway         inet not null,
    dns             jsonb not null default '["8.8.8.8"]'::jsonb,
    -- Never handed out: [{"from": "a.b.c.d", "to": "a.b.c.e"}, ...] (inclusive).
    reserved_ranges jsonb not null default '[]'::jsonb,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

create table ip_allocations (
    id           bigint generated always as identity primary key,
    pool_id      bigint not null references ip_pools (id),
    -- The unique index is the concurrent-allocation guard: allocate() claims
    -- fresh IPs with INSERT .. ON CONFLICT (ip) DO NOTHING and treats a
    -- conflict as "already taken, try the next candidate".
    ip           inet not null unique,
    vm_id        bigint references vms (id),
    status       allocation_status not null default 'ALLOCATED',
    allocated_at timestamptz not null default now(),
    -- Set on release; reuse only after settings.ip_quarantine_hours elapsed.
    released_at  timestamptz
);

create index ip_allocations_pool_id_status_idx on ip_allocations (pool_id, status);

-- ── FKs promised by the V3/V4 column comments ──
alter table nodes
    add constraint nodes_ip_pool_id_fkey foreign key (ip_pool_id) references ip_pools (id);
comment on column nodes.ip_pool_id is 'IP pool VMs on this node allocate from.';

alter table vms
    add constraint vms_ip_allocation_id_fkey foreign key (ip_allocation_id) references ip_allocations (id);

-- No reference seed here. A pool's CIDR, gateway and DNS belong to the network the
-- platform is deployed on, and the node it attaches to is registered by the same
-- operations script. See the migration convention.

-- ── Settings key read by IpamService. Operator-tunable at runtime. ──
insert into settings (key, value, description) values
    ('ip_quarantine_hours', '24'::jsonb,
     'Hours a RELEASED IP stays unassignable before reuse (IPAM quarantine).');
