-- Per-user SSH public keys. Registered
-- keys are the primary SSH-gateway auth method: the route lookup matches the
-- offered key's SHA-256 fingerprint to a row here and attributes the session to
-- its owner. A key may also be server-generated ("키 만들기"), in which case the
-- private key is kept as an AES-256-GCM ciphertext for re-download — the same
-- reversible-credential frame as the VM password (CredentialCipher, env key).

create table user_ssh_keys (
    id                 bigint generated always as identity primary key,
    user_id            bigint not null references users (id) on delete cascade,
    name               text not null,
    algorithm          text not null check (algorithm in ('ssh-ed25519', 'ssh-rsa')),
    public_key         text not null,
    fingerprint_sha256 text not null,
    private_key_enc    text,
    created_at         timestamptz not null default now(),
    last_used_at       timestamptz
);

-- Fingerprints are globally unique: the route audit's per-user attribution
-- relies on a fingerprint mapping to exactly one owner (a duplicate would make
-- "who connected" ambiguous), so registration rejects an already-known
-- fingerprint regardless of owner.
create unique index user_ssh_keys_fingerprint_uq on user_ssh_keys (fingerprint_sha256);
create index user_ssh_keys_user_id_idx on user_ssh_keys (user_id);

comment on column user_ssh_keys.algorithm is
    'OpenSSH key-type token (ssh-ed25519 | ssh-rsa). The console SshKeyAlgorithm enum (ED25519/RSA) maps from this.';
comment on column user_ssh_keys.public_key is
    'Normalized OpenSSH public one-liner (<type> <base64>, comment stripped at registration). Public info — safe to re-display.';
comment on column user_ssh_keys.fingerprint_sha256 is
    'OpenSSH SHA-256 fingerprint (SHA256:<base64 no padding>), identical to ssh-keygen -lf. Globally unique — the route lookup key and audit identity.';
comment on column user_ssh_keys.private_key_enc is
    'AES-256-GCM ciphertext (v1:<iv>:<ct||tag>, key = PICKLE_CREDENTIALS_KEY) of the openssh-key-v1 private PEM — only for server-generated keys; NULL for pasted keys (server never holds their private key).';
comment on column user_ssh_keys.last_used_at is
    'Last successful SSH-gateway auth with this key (best-effort update). Helps users prune unused keys.';
