-- M3 WP-B5: the contract's VmDeletion.requestedAt (when the deletion was
-- accepted/scheduled) has no V6 column — grace math uses delete_scheduled_for,
-- but the acceptance time must survive setting changes and be reportable.
alter table vms
    add column delete_requested_at timestamptz;

comment on column vms.delete_requested_at is
    'When the pending deletion was accepted/scheduled (contract VmDeletion.requestedAt); cleared together with the other delete_* columns on cancel.';
