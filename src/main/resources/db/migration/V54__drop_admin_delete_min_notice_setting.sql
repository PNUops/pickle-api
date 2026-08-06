-- The scheduled-deletion minimum-notice floor is gone (operator decision
-- 2026-07-27): a deletion needed within the notice window previously had no
-- path except the immediate force delete, erasing the cancellable middle
-- state (notification + grace until the sweeper fires). Scheduling now only
-- requires a future instant; the console warns below the recommended 7 days
-- instead of blocking. The setting row leaves with its enforcement — a
-- setting nothing reads must not sit in the settings screen.
--
-- The delete that removed the row is gone with the seed that created it: the
-- settings table is deployment content an operator bootstrap script writes, and
-- a key the product no longer has is simply never written. The column comment
-- below is schema, so it stays.

comment on column vms.delete_scheduled_for is
    'When the deletion sweeper may hard-delete: self-delete now()+vm_delete_grace_hours, admin delete at any operator-chosen future instant (minimum-notice floor dropped in V54).';
