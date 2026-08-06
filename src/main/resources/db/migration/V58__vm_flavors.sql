-- Split the OS axis from the spec axis (2026-07-28 operator decision, pulled
-- forward from the second-OS backlog item): vm_templates becomes a pure OS
-- catalog (which Proxmox image to clone + its disk floor), and the request
-- form's spec presets move to the new vm_flavors table. Without this, every
-- added OS multiplies the preset rows (5 OS x 3 presets = 15 cards).
-- The V3 seed's three rows were one OS x three presets: the plain
-- 'ubuntu-24.04' row survives as the OS entry, the -small/-large rows are
-- folded into it after their FK references are repointed.

create table vm_flavors (
    id bigint generated always as identity primary key,
    name text not null unique,
    display_name text not null,
    vcpu int not null check (vcpu > 0),
    memory_mb int not null check (memory_mb >= 256),
    disk_gb int not null check (disk_gb > 0),
    notes text,
    -- Same lifecycle semantics as templates: ACTIVE = shown in the request
    -- wizard, DISABLED = retired (existing requests/VMs unaffected).
    status template_status not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- No presets are seeded. Which sizes a deployment offers, and what they cost in
-- vCPU, memory and disk, is a policy the operator sets against the hardware they
-- actually have -- three sizes sensible on one host are wrong on another. The
-- admin console creates and edits them (list, create, edit, status), so this is
-- one of the tables that has a real write path and therefore needs no seed. The
-- dev/test seeder fills an empty table so a development database is usable.

-- Provenance of the chosen preset (specs themselves stay denormalized on
-- vm_requests/vm_request_reviews/vms). Nullable in the DB — @NotNull on the
-- DTO — so this backfill stays idempotent-shaped; every existing row gets one.
alter table vm_requests add column flavor_id bigint references vm_flavors (id);

update vm_requests r
   set flavor_id = f.id
  from vm_templates t, vm_flavors f
 where t.id = r.template_id
   and f.name = case t.name
                    when 'ubuntu-24.04-small' then 'small'
                    when 'ubuntu-24.04-large' then 'large'
                    else 'basic'
                end;

-- The consolidation that stood here folded three seeded catalog rows into one
-- and repointed every foreign key off the two it removed. All of it is gone
-- with the seed: no migration inserts a catalog row, so there were no preset
-- rows to fold and no keys pointing at them. What the change was really about
-- — that size is a separate axis from the image — survives as the schema
-- below.

alter table vm_templates
    drop column default_vcpu,
    drop column default_memory_mb,
    drop column default_disk_gb;
