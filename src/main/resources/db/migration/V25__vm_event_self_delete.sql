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
