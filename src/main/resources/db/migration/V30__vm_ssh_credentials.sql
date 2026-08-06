-- Pinned SSH host key per VM (the per-user SSH gateway route contract). The
-- provisioning HOSTKEY step reads the guest's /etc/ssh/ssh_host_ed25519_key.pub
-- (via the guest agent) and stores it here; the SSH gateway pins the upstream
-- host key against this value instead of IgnoreHostKey. A VM without a collected
-- host key (VMs not yet re-provisioned) is denied at route resolution
-- rather than piped unverified — re-provision to populate it.

alter table vms
    add column ssh_host_key text;

comment on column vms.ssh_host_key is
    'VM SSH host public key in authorized_keys one-line form (ssh-ed25519 <b64>), collected at provisioning (HOSTKEY step) via the guest agent. NULL → route resolution denies (SSHGW_NO_HOST_KEY); populated on (re-)provisioning.';

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

-- Host-key pinning fix (2026-07-18): pin ALL of a VM's host-key types, not just
-- ed25519. sshpiperd's stock upstream client advertises ecdsa ahead of ed25519
-- and cannot be constrained per-plugin, so a VM presents its ecdsa host key on
-- the gateway→VM hop even though it also has an ed25519 one — pinning ed25519
-- alone fail-closed every session. The HOSTKEY provisioning step now reads every
-- /etc/ssh/ssh_host_*_key.pub and stores them newline-joined in this same column
-- (no schema change); the route API splits it into the hostKeys array.

comment on column vms.ssh_host_key is
    'VM SSH host public key(s), one per host-key type the VM presents (ed25519/ecdsa/rsa), newline-joined, each in authorized_keys one-line form. Collected at provisioning (HOSTKEY step reads all /etc/ssh/ssh_host_*_key.pub). All types are pinned because sshpiperd advertises ecdsa ahead of ed25519 on the upstream hop and cannot be constrained per-plugin. NULL/blank → route denies (SSHGW_NO_HOST_KEY). The route API splits it into the hostKeys array.';
