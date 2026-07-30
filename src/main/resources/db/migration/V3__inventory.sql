-- Infrastructure inventory: nodes and vm_templates, plus reference
-- seed rows (single pve1 node, the three template presets) and the
-- settings keys later phases read.

create type node_status as enum ('ACTIVE', 'MAINTENANCE', 'OFFLINE');
create type template_status as enum ('ACTIVE', 'DISABLED');

create table nodes (
    id          bigint generated always as identity primary key,
    name        text not null unique,
    api_host    text not null,
    status      node_status not null default 'ACTIVE',
    cpu_threads int not null,
    memory_mb   int not null,
    labels      jsonb not null default '{}'::jsonb,
    vm_bridge   text not null,
    storage     text not null,
    ip_pool_id  bigint,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

comment on column nodes.ip_pool_id is
    'Will reference ip_pools(id); that table lands with the IPAM migration, which also adds the FK.';

create table vm_templates (
    id                bigint generated always as identity primary key,
    name              text not null,
    display_name      text not null,
    proxmox_vmid      int not null,
    node_id           bigint not null references nodes (id),
    version           int not null default 1,
    default_vcpu      int not null,
    default_memory_mb int not null,
    default_disk_gb   int not null,
    min_disk_gb       int not null,
    status            template_status not null default 'ACTIVE',
    notes             text,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    -- Template updates create a new version row; old versions are DISABLED.
    unique (name, version)
);

create index vm_templates_node_id_idx on vm_templates (node_id);

-- No reference seed here. A node row and an OS catalog row describe the host the
-- platform runs on, not its schema: on any other host they name an API address,
-- a capacity and a template id that are wrong rather than merely unhelpful, and
-- placement reads that capacity as a hard filter. An operations script registers
-- the real ones; the dev/test seeder supplies equivalents where nobody runs that
-- script. See the migration convention.

-- ── Settings keys read by the request wizard and approval/publishing
--    phases. Values are operator-tunable at runtime. ──
insert into settings (key, value, description) values
    ('allowed_root_domains', '["pickle.pnuops.com"]'::jsonb,
     'Root domains selectable as rootDomain in VM requests (GET /meta/request-options).'),
    ('reserved_subdomains', '["www","api","admin","ssh","mail","console","staging"]'::jsonb,
     'Subdomains that can never be requested as desiredSubdomain.'),
    ('vcpu_overcommit_warn', '3.0'::jsonb,
     'Approval-context warning threshold: allocated vCPU / physical threads.'),
    ('memory_usage_warn', '0.8'::jsonb,
     'Approval-context warning threshold: allocated memory / physical memory.');
