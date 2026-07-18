-- M5.5 gate fix (2026-07-18): pin ALL of a VM's host-key types, not just
-- ed25519. sshpiperd's stock upstream client advertises ecdsa ahead of ed25519
-- and cannot be constrained per-plugin, so a VM presents its ecdsa host key on
-- the gateway→VM hop even though it also has an ed25519 one — pinning ed25519
-- alone fail-closed every session. The HOSTKEY provisioning step now reads every
-- /etc/ssh/ssh_host_*_key.pub and stores them newline-joined in this same column
-- (no schema change); the route API splits it into the hostKeys array.

comment on column vms.ssh_host_key is
    'VM SSH host public key(s), one per host-key type the VM presents (ed25519/ecdsa/rsa), newline-joined, each in authorized_keys one-line form. Collected at provisioning (HOSTKEY step reads all /etc/ssh/ssh_host_*_key.pub). All types are pinned because sshpiperd advertises ecdsa ahead of ed25519 on the upstream hop and cannot be constrained per-plugin. NULL/blank → route denies (SSHGW_NO_HOST_KEY). The route API splits it into the hostKeys array (docs/api/internal.md Link 1).';
