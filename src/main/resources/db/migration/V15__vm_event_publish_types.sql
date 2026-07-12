-- Publish/unpublish entries in the permanent per-VM history (docs/plan/02
-- vm_events). Kept in its own migration: PostgreSQL allows ALTER TYPE ... ADD
-- VALUE inside a transaction only when the new value is not used in the same
-- transaction, so the enum grows here and is first used at runtime.

alter type vm_event_type add value if not exists 'PUBLISH';
alter type vm_event_type add value if not exists 'UNPUBLISH';
