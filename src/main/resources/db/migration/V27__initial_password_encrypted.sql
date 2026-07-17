-- 2026-07-17 policy change (product-spec §8): the one-shot initial-password
-- reveal is retired. The plaintext-until-first-view column is replaced by a
-- reversible AES-256-GCM ciphertext (key = PICKLE_CREDENTIALS_KEY, env only)
-- so the console can show the password again at any time; every reveal is
-- still audited. No backfill: pre-launch, and no VMs exist at migration time
-- (dev DB reset 2026-07-17, smoke VMs are destroyed after each run).

alter table vms
    drop column initial_password,
    add column initial_password_enc text;

comment on column vms.initial_password_enc is
    'AES-256-GCM ciphertext (v1:<iv>:<ct||tag>, key = PICKLE_CREDENTIALS_KEY from /etc/pickle/api.env). Reversible by design — the always-re-viewable policy (2026-07-17) replaced the one-shot plaintext column. NULLed on deletion like the old plaintext.';
comment on column vms.initial_password_hash is
    'BCrypt hash of the current initial password, kept for support verification without exposing the value.';
comment on column vms.initial_password_viewed_at is
    'Last reveal time (no longer a one-shot consumption marker since the 2026-07-17 policy change).';
