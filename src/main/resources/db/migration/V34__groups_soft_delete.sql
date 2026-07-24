-- Group soft delete (also used by account withdrawal). Group rows are
-- referenced by VM history and audit trails, so deletion keeps the row and
-- stamps deleted_at. The slug uniqueness moves to a partial index over live
-- rows so a new group can reuse a deleted group's slug (contract: deleteGroup).
-- Shared because account withdrawal soft-deletes the PERSONAL
-- group through the same column.

alter table groups
    add column deleted_at timestamptz,
    add column deleted_by bigint references users (id);

comment on column groups.deleted_at is
    'Soft-delete stamp. Deleted groups keep their row for VM/audit history but disappear from every list and lookup.';
comment on column groups.deleted_by is
    'Who deleted the group: the OWNER (deleteGroup) or the withdrawing user (PERSONAL group cleanup).';

alter table groups drop constraint groups_slug_key;
create unique index groups_slug_live_uniq on groups (slug) where deleted_at is null;
