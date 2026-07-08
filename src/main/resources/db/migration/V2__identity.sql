-- Identity & auth foundation (WP-B1): users, orgs, groups, verification/refresh
-- tokens, audit log, and the PostgreSQL rate-limit counter table.
-- See docs/plan/02-data-model.md and docs/plan/07-security.md.

create extension if not exists citext;

create type user_role as enum ('STUDENT', 'ORG_ADMIN', 'SYS_ADMIN');
create type user_status as enum ('PENDING_VERIFICATION', 'ACTIVE', 'DISABLED', 'WITHDRAWN');
create type org_status as enum ('ACTIVE', 'DISABLED');
create type group_kind as enum ('PERSONAL', 'TEAM', 'PROJECT');
create type group_member_role as enum ('OWNER', 'MANAGER', 'MEMBER', 'VIEWER');
create type verification_purpose as enum ('SIGNUP', 'PASSWORD_RESET');

create table orgs (
    id          bigint generated always as identity primary key,
    name        text not null,
    slug        text not null unique,
    description text,
    status      org_status not null default 'ACTIVE',
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create table users (
    id                bigint generated always as identity primary key,
    email             citext not null unique,
    password_hash     text not null,
    name              text not null,
    role              user_role not null default 'STUDENT',
    org_id            bigint references orgs (id),
    status            user_status not null default 'PENDING_VERIFICATION',
    email_verified_at timestamptz,
    token_version     int not null default 0,
    withdrawn_at      timestamptz,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

create index users_org_id_idx on users (org_id);

create table groups (
    id          bigint generated always as identity primary key,
    kind        group_kind not null,
    name        text not null,
    slug        text not null unique,
    description text,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create table group_members (
    id         bigint generated always as identity primary key,
    group_id   bigint not null references groups (id),
    user_id    bigint not null references users (id),
    role       group_member_role not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (group_id, user_id)
);

create index group_members_user_id_idx on group_members (user_id);

create table email_verifications (
    id         bigint generated always as identity primary key,
    user_id    bigint not null references users (id),
    token_hash text not null unique,
    purpose    verification_purpose not null,
    expires_at timestamptz not null,
    used_at    timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index email_verifications_user_id_idx on email_verifications (user_id);

create table refresh_tokens (
    id           bigint generated always as identity primary key,
    user_id      bigint not null references users (id),
    token_hash   text not null unique,
    expires_at   timestamptz not null,
    rotated_from bigint references refresh_tokens (id),
    revoked_at   timestamptz,
    user_agent   text,
    ip           text,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

create index refresh_tokens_user_id_idx on refresh_tokens (user_id);
create index refresh_tokens_rotated_from_idx on refresh_tokens (rotated_from);

-- Append-only audit trail (07: the app DB role gets no UPDATE/DELETE grant on
-- this table; enforced by V7__audit_append_only.sql since M3, previously
-- applied by ops on managed environments).
-- actor_id is intentionally NOT a foreign key: audit rows are written from
-- independent transactions (they must not depend on uncommitted business rows)
-- and keep the opaque user id even after account anonymization (02).
create table audit_logs (
    id          bigint generated always as identity primary key,
    actor_id    bigint,
    actor_role  text,
    action      text not null,
    target_type text,
    target_id   bigint,
    detail      jsonb,
    ip          text,
    created_at  timestamptz not null default now()
);

create index audit_logs_action_created_at_idx on audit_logs (action, created_at);
create index audit_logs_actor_id_idx on audit_logs (actor_id);

-- Sliding-window counters + escalating login lockout (07: PG counter table, no
-- Redis). Counter rows use time-bucketed window_start; the login-lockout row per
-- account uses a fixed epoch window_start and carries locked_until.
create table auth_rate_limits (
    id            bigint generated always as identity primary key,
    scope         text not null,
    subject       text not null,
    window_start  timestamptz not null,
    request_count int not null default 0,
    locked_until  timestamptz,
    updated_at    timestamptz not null default now(),
    unique (scope, subject, window_start)
);

create index auth_rate_limits_window_idx on auth_rate_limits (window_start);
