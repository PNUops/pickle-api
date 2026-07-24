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
