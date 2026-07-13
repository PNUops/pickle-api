-- Expiry entries in the permanent per-VM history (M5). Own migration per the
-- V15 precedent: ALTER TYPE ... ADD VALUE may not share a transaction with a
-- statement that uses the new value, so the enum grows here and is first used
-- at runtime.

alter type vm_event_type add value if not exists 'EXPIRE_STOP';
alter type vm_event_type add value if not exists 'PERIOD_UPDATE';
