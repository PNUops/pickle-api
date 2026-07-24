-- Drift findings: persisted DB↔Proxmox drift report rows written by
-- DriftReconciler each 10-minute cycle. One OPEN row per (kind, dedup_key);
-- re-observation bumps last_seen_at, disappearance auto-resolves (resolved_by
-- null), and admins may resolve manually with a note. The drift report ops
-- are exposed in the contract.

create type drift_finding_kind as enum
    ('MISSING_IN_PROXMOX', 'UNMANAGED_GUEST', 'SPEC_MISMATCH');
create type drift_finding_status as enum ('OPEN', 'RESOLVED');

create table drift_findings (
    id              bigint generated always as identity primary key,
    kind            drift_finding_kind not null,
    -- Related VM for kinds ① MISSING_IN_PROXMOX / ③ SPEC_MISMATCH; null for
    -- ② UNMANAGED_GUEST (the whole point is that the DB does not know it).
    vm_id           bigint references vms (id),
    proxmox_vmid    int,
    node_name       text,
    summary         text not null, -- Korean one-liner
    detail          jsonb,
    status          drift_finding_status not null default 'OPEN',
    first_seen_at   timestamptz not null default now(),
    last_seen_at    timestamptz not null default now(),
    resolved_at     timestamptz,
    -- Null on a RESOLVED row = auto-resolved by the reconciler.
    resolved_by     bigint references users (id),
    resolution_note text,
    -- 'vm:<id>' for kinds ①/③, 'vmid:<n>' for ② — identity of the observed
    -- condition, so re-observation upserts instead of duplicating.
    dedup_key       text not null
);

-- At most one OPEN finding per observed condition; resolved rows are history.
create unique index drift_findings_open_dedup_uq
    on drift_findings (kind, dedup_key)
 where status = 'OPEN';

create index drift_findings_status_last_seen_idx
    on drift_findings (status, last_seen_at desc);
