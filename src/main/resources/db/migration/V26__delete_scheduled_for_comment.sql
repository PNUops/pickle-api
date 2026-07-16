-- Terminology standardization follow-up (2026-07-17): the V6 column comment on
-- vms.delete_scheduled_for still referenced the retired settings key name
-- admin_delete_min_notice_days (renamed to vm_admin_delete_min_notice_days in
-- V24). Re-issue the comment with the current key; wording otherwise keeps
-- V6's original intent.

comment on column vms.delete_scheduled_for is
    'When the deletion sweeper may hard-delete: self-delete now()+vm_delete_grace_hours, admin delete >= vm_admin_delete_min_notice_days out.';
