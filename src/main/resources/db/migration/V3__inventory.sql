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

-- ── Reference seed: the single Proxmox node ──
insert into nodes (name, api_host, cpu_threads, memory_mb, vm_bridge, storage)
values ('pve1', 'https://172.30.0.1:8006', 40, 79872, 'vmbr2', 'local-lvm');

-- ── Reference seed: request-form template presets ──
insert into vm_templates
    (name, display_name, proxmox_vmid, node_id, default_vcpu, default_memory_mb,
     default_disk_gb, min_disk_gb, notes)
select t.name, t.display_name, 9000, n.id, t.vcpu, t.memory_mb, t.disk_gb, 10, t.notes
  from nodes n,
       (values
           ('ubuntu-24.04',       'Ubuntu 24.04 LTS (기본형)', 2, 2048, 20,
            '대부분의 수업·동아리 프로젝트에 적합합니다.'),
           ('ubuntu-24.04-small', 'Ubuntu 24.04 LTS (소형)',   1, 1024, 10,
            '봇, 크론 작업 등 초소형 서비스에 적합합니다.'),
           ('ubuntu-24.04-large', 'Ubuntu 24.04 LTS (대형)',   4, 8192, 40,
            '대형 스펙은 신청 시 사용 사유를 반드시 적어 주세요.')
       ) as t (name, display_name, vcpu, memory_mb, disk_gb, notes)
 where n.name = 'pve1';

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
