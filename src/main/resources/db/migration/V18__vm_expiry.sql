-- VM usage-period expiry: markers for the hourly expiry job. endDate is
-- inclusive (usable through the end date, KST); auto-stop runs after midnight
-- KST of the following day. Extending the period clears both markers so the
-- VM can start again and notices re-arm.

alter table vms
    add column expiry_stopped_at timestamptz,
    add column last_expiry_notice_stage int;

comment on column vms.expiry_stopped_at is
    'Set when the expiry sweeper auto-stopped this VM. Cleared by PATCH /admin/vms/{vmId}/period so an extended VM may start again.';
comment on column vms.last_expiry_notice_stage is
    'Smallest D-day stage (days before end_date) already notified for the current end_date; the CAS "stage < current" guard makes hourly re-runs send nothing.';

-- The vm_expiry_autostop_enabled switch is not seeded here. Whether an expired VM
-- is stopped automatically or only warned about is an operator policy decision,
-- so an operator bootstrap script writes the row and the dev/test seeder supplies
-- an equivalent.
