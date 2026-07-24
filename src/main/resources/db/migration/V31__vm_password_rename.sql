-- With password regeneration (POST /vms/{vmId}/password/regenerate) the
-- "initial" qualifier is retired — the stored value is simply the VM's current
-- password. Rename the columns to drop it (contract v0.8.0 schema rename
-- InitialPasswordResponse→VmPasswordResponse, VmDetail.initialPasswordAvailable→
-- passwordAvailable). Pure rename: data, semantics, and the reversible-ciphertext
-- design are unchanged; the reveal-viewed timestamp stays informational.

alter table vms rename column initial_password_enc to password_enc;
alter table vms rename column initial_password_hash to password_hash;
alter table vms rename column initial_password_viewed_at to password_viewed_at;

comment on column vms.password_enc is
    'AES-256-GCM ciphertext (v1:<iv>:<ct||tag>, key = PICKLE_CREDENTIALS_KEY) of the current VM password. Reversible by design (always-re-viewable policy). NULLed on deletion. Updated in place by password regeneration.';
comment on column vms.password_hash is
    'BCrypt hash of the current VM password, kept for support verification without exposing the value.';
comment on column vms.password_viewed_at is
    'Last reveal time (informational since the 2026-07-17 re-viewable policy; not a one-shot consumption marker).';
