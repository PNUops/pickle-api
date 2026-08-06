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

-- No settings seed here. The request wizard and the approval/publishing phases
-- read allowed_root_domains, reserved_subdomains, vcpu_overcommit_warn and
-- memory_usage_warn: a root domain names a zone this deployment owns and the two
-- thresholds are judgements about real hardware, so all four are deployment
-- content rather than schema. An operator bootstrap script writes them; the
-- dev/test seeder supplies equivalents. See the migration convention.
