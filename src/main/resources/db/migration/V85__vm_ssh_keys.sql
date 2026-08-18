-- SSH keys move from the account to the (user, VM) pair.
--
-- A single account key opened every VM its owner could reach, so one leaked key
-- leaked all of them. Scoping the key to one VM bounds the blast radius, and it
-- costs nothing at the guest: user keys are never written to a VM's
-- authorized_keys — the gateway resolves the offered fingerprint against this
-- table and re-authenticates to the guest with the platform key.
--
-- Drop and recreate rather than alter: the only columns that survive unchanged
-- are the fingerprint and the ciphertext, while vm_id arrives NOT NULL, name
-- disappears (a key belongs to a VM, so there is nothing for a user to name) and
-- private_key_enc stops being nullable now that pasted keys are gone. Six alters
-- would describe the destination less clearly than one create, and no row is
-- worth keeping: every key here was issued under the account-wide model.
drop table user_ssh_keys;

create table vm_ssh_keys (
    id                 bigint generated always as identity primary key,
    public_id          uuid not null default gen_random_uuid(),
    vm_id              bigint not null references vms (id) on delete cascade,
    user_id            bigint not null references users (id) on delete cascade,
    algorithm          text not null default 'ssh-ed25519'
                       check (algorithm = 'ssh-ed25519'),
    public_key         text not null,
    fingerprint_sha256 text not null,
    private_key_enc    text not null,
    created_at         timestamptz not null default now(),
    last_used_at       timestamptz
);

create unique index vm_ssh_keys_public_id_uidx on vm_ssh_keys (public_id);

-- Exactly one live key per person per VM. Re-issuing replaces the row, which is
-- what makes re-issue the user's own revocation of a leaked private key.
create unique index vm_ssh_keys_vm_user_uidx on vm_ssh_keys (vm_id, user_id);

-- Globally unique so a fingerprint resolves to exactly one owner. Both the
-- gateway's identity lookup and the session attribution rule depend on that
-- being a function rather than a relation.
create unique index vm_ssh_keys_fingerprint_uidx on vm_ssh_keys (fingerprint_sha256);

create index vm_ssh_keys_user_id_idx on vm_ssh_keys (user_id);

comment on table vm_ssh_keys is
    'Per (user, VM) ed25519 keypair issued by the platform; the private PEM is stored as AES-GCM ciphertext for re-download.';
comment on column vm_ssh_keys.fingerprint_sha256 is
    'SHA256:<base64> as ssh-keygen -lf prints it; the SSH gateway''s identity lookup key.';
comment on column vm_ssh_keys.private_key_enc is
    'CredentialCipher ciphertext of the OpenSSH private PEM. Never logged, never audited.';
