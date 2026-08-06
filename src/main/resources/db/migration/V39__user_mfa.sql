-- 2FA (TOTP) enrollment state + login step-up tokens.
--
-- One user_mfa row per user carries both the ACTIVE secret (set on activation)
-- and a PENDING secret for a begun-but-not-yet-activated setup, so a repeated
-- "begin" simply overwrites the pending secret without disturbing an existing
-- enrollment. enabled_at not null is the single "enrolled" predicate.
--
-- Secrets are AES-256-GCM ciphertext (CredentialCipher frame, v1:iv:ct) — a DB
-- dump alone cannot recover a TOTP secret. The raw Base32 secret is shown to the
-- user exactly once (begin response) and never again.

create table user_mfa (
    user_id            bigint primary key references users (id),
    totp_secret_enc    text,
    enabled_at         timestamptz,
    pending_secret_enc text,
    pending_created_at timestamptz,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now()
);

comment on table user_mfa is
    '2FA(TOTP) enrollment. enabled_at not null = enrolled; pending_secret_enc = begun-but-not-activated setup.';
comment on column user_mfa.totp_secret_enc is
    'Active TOTP secret, AES-256-GCM (CredentialCipher). Null until activation.';
comment on column user_mfa.pending_secret_enc is
    'Secret issued by begin, not yet confirmed. Cleared on activation; overwritten by a repeated begin.';

-- Login step-up: /auth/login returns one of these (5-min, single-use) for an
-- enrolled account; /auth/mfa consumes it on a correct code. A wrong code does
-- NOT consume the token (retryable); expiry/consumption both yield 410.
create table mfa_login_tokens (
    id          bigint generated always as identity primary key,
    user_id     bigint not null references users (id),
    token_hash  text not null unique,
    expires_at  timestamptz not null,
    consumed_at timestamptz,
    created_at  timestamptz not null default now()
);

create index mfa_login_tokens_user_id_idx on mfa_login_tokens (user_id);

comment on table mfa_login_tokens is
    'Single-use 5-minute login step-up tokens: issued by /auth/login for an enrolled account, consumed by /auth/mfa. token_hash = sha256 hex of the raw token.';

-- One-time 2FA recovery codes. Ten are minted at activation (and on
-- regenerate, which invalidates every prior code) and shown to the user exactly
-- once. Each code is stored only as a BCrypt hash — the plaintext is never
-- recoverable — and single-use (used_at stamps consumption).

create table mfa_recovery_codes (
    id         bigint generated always as identity primary key,
    user_id    bigint not null references users (id),
    code_hash  text not null,
    used_at    timestamptz,
    created_at timestamptz not null default now()
);

create index mfa_recovery_codes_user_id_idx on mfa_recovery_codes (user_id);

comment on table mfa_recovery_codes is
    '2FA recovery codes: BCrypt-hashed, single-use (used_at). Regenerate deletes all prior rows.';
