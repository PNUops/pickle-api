-- SELF_DELETE: acceptance event for user self-deletion (terminology
-- standardization 2026-07-16). Acceptance events are now per-kind
-- (SELF_DELETE / SCHEDULE_DELETE / FORCE_DELETE); the terminal purge event
-- stays the shared DELETE. Historical DELETE rows keep their mixed meaning
-- (no backfill — the detail text disambiguates).
--
-- Own migration per the V15/V19 precedent: ALTER TYPE ... ADD VALUE may not
-- share a transaction with a statement that uses the new value; the enum
-- grows here and is first used at runtime.

alter type vm_event_type add value if not exists 'SELF_DELETE';

-- Terminology standardization follow-up (2026-07-17): the V6 column comment on
-- vms.delete_scheduled_for still referenced the retired settings key name
-- admin_delete_min_notice_days (renamed to vm_admin_delete_min_notice_days in
-- V24). Re-issue the comment with the current key; wording otherwise keeps
-- V6's original intent.

comment on column vms.delete_scheduled_for is
    'When the deletion sweeper may hard-delete: self-delete now()+vm_delete_grace_hours, admin delete >= vm_admin_delete_min_notice_days out.';
