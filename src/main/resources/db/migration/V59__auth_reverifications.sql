-- Sudo-mode reauthentication (2026-07-28): the real session lifetime is the
-- 14-day refresh cookie, so sensitive operations (VM password reveal/regen,
-- SSH private-key download, VM delete/settings, SSH key management, group
-- membership changes) demand a fresh password proof. POST /auth/reverify
-- verifies the password and issues a MULTI-use 10-minute token (a whole
-- sensitive workflow needs one prompt, not one per call) sent back as the
-- X-Reauth-Token header. token_version is pinned at issue: any bump
-- (password change/reset, withdrawal, disable, role change) invalidates
-- outstanding reauth tokens with zero extra call sites — the same mechanism
-- that kills access tokens.

create table auth_reverifications (
    id            bigint generated always as identity primary key,
    user_id       bigint not null references users (id),
    token_hash    text not null unique,
    token_version int not null,
    expires_at    timestamptz not null,
    created_ip    text,
    created_at    timestamptz not null default now()
);

create index auth_reverifications_user_id_idx on auth_reverifications (user_id);

comment on table auth_reverifications is
    'Multi-use 10-minute sudo-mode tokens: issued by /auth/reverify after a password check, required (X-Reauth-Token) by @RequireReauth endpoints. token_hash = sha256 hex of the raw token; token_version must match the user''s current value.';
