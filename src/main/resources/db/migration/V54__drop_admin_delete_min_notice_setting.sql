-- The scheduled-deletion minimum-notice floor is gone (operator decision
-- 2026-07-27): a deletion needed within the notice window previously had no
-- path except the immediate force delete, erasing the cancellable middle
-- state (notification + grace until the sweeper fires). Scheduling now only
-- requires a future instant; the console warns below the recommended 7 days
-- instead of blocking. The setting row leaves with its enforcement — a
-- setting nothing reads must not sit in the settings screen.

delete from settings where key = 'vm_admin_delete_min_notice_days';

comment on column vms.delete_scheduled_for is
    'When the deletion sweeper may hard-delete: self-delete now()+vm_delete_grace_hours, admin delete at any operator-chosen future instant (minimum-notice floor dropped in V54).';
