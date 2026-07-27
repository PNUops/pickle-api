-- GATEWAY_BLOCK / GATEWAY_UNBLOCK: per-VM SSH-gateway/web-terminal block
-- transitions gain an admin API (contract v0.16.0), and the flag flips land in
-- the permanent per-VM history so members can see when and why access closed.
--
-- Own migration per the V15/V19/V25 precedent: ALTER TYPE ... ADD VALUE may
-- not share a transaction with a statement that uses the new value; the enum
-- grows here and is first used at runtime.

alter type vm_event_type add value if not exists 'GATEWAY_BLOCK';
alter type vm_event_type add value if not exists 'GATEWAY_UNBLOCK';
