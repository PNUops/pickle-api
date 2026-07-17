-- Pinned SSH host key per VM (M5.5, docs/api/internal.md Link 1 v2). The
-- provisioning HOSTKEY step reads the guest's /etc/ssh/ssh_host_ed25519_key.pub
-- (via the guest agent) and stores it here; the SSH gateway pins the upstream
-- host key against this value instead of IgnoreHostKey. A VM without a collected
-- host key (pre-M5.5 VMs not yet re-provisioned) is denied at route resolution
-- rather than piped unverified — re-provision to populate it.

alter table vms
    add column ssh_host_key text;

comment on column vms.ssh_host_key is
    'VM SSH host public key in authorized_keys one-line form (ssh-ed25519 <b64>), collected at provisioning (HOSTKEY step) via the guest agent. NULL → route resolution denies (SSHGW_NO_HOST_KEY); populated on (re-)provisioning.';
