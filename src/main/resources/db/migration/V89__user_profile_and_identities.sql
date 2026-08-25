-- Google sign-in and the profile the console collects at signup.
--
-- Three parts, one feature: the profile columns (직책·학번·소속), the linked
-- identity table Google logins hang off, and dropping NOT NULL from
-- password_hash so an account can exist without one. No rows are inserted.
--
-- The position catalogue and the department catalogue are NOT here. Positions
-- are a Java enum (the contract has to publish them), departments are a
-- classpath resource served by GET /meta/profile-options. Neither is data that
-- describes this host, and a migration that seeded them would make an empty
-- table block signup on every fresh database.

create type user_position as enum (
    'STUDENT_UNDERGRAD',
    'STUDENT_GRADUATE',
    'RESEARCHER',
    'PROFESSOR',
    'STAFF',
    'OTHER'
);

-- All three are nullable: every existing row predates them and a migration
-- does not backfill. Existing accounts fill them in through PUT /me/profile,
-- which is also what the console's profile gate drives.
alter table users
    add column position         user_position,
    add column student_no       varchar(20),
    add column department_code  varchar(32);

-- An implication, deliberately not the biconditional chk_users_org_role (V44)
-- uses. Two reasons the '=' form would be wrong here:
--   1. every existing row has position is null, so a biconditional is violated
--      the moment this migration commits;
--   2. an undergraduate who becomes STAFF keeps the 학번 they were issued —
--      it is a historical fact, and '=' would force it to null.
-- The claim this constraint actually makes is the whole of what we believe:
-- a student row without a 학번 cannot exist.
--
-- position::text rather than the enum literal, following the V44/V20 note: a
-- later ALTER TYPE ... ADD VALUE cannot use the new value in the transaction
-- that adds it, so the CHECK that migration has to rewrite must already be in
-- the text-comparison form.
alter table users
    add constraint chk_users_student_no
    check (position is null
        or position::text not in ('STUDENT_UNDERGRAD', 'STUDENT_GRADUATE')
        or student_no is not null);

-- No unique constraint on student_no, on purpose. One typo would permanently
-- claim a real student's number with no admin path to correct it, and the 409
-- it would answer with is an enumeration oracle over a sequential value.
-- Duplicate detection belongs in an admin report, not in a constraint.

comment on column users.position is
    '직책. null인 행은 프로필을 아직 채우지 않은 계정이다.';
comment on column users.student_no is
    '학번. 학생 직책에서만 필수이며 형식은 검증하지 않는다(편입·재입학·교환학생).';
comment on column users.department_code is
    '소속 학과 코드. 카탈로그는 앱 리소스에 있고 FK를 걸지 않는다 — 학과명이 바뀌어도 이 값은 그대로다.';

-- An account may now exist without a password: one created through Google
-- sign-in has never had one. Everything that compares a supplied password
-- against this column has to handle null. The encoder returns false for a null
-- hash without running BCrypt, so the hazard is timing rather than an error:
-- AuthService.login burns its equaliser hash so that a passwordless account is
-- not identifiable by how fast it is refused.
alter table users alter column password_hash drop not null;

-- Linked external identities. Keyed on the provider's subject rather than the
-- e-mail: a Workspace address can change while `sub` does not, and that is what
-- lets a rename keep working without touching users.email (which invitations,
-- audit and notifications use to name a person).
create table user_identities (
    id             bigserial primary key,
    user_id        bigint       not null references users (id) on delete cascade,
    provider       varchar(20)  not null,
    subject        varchar(255) not null,
    email_at_link  citext       not null,
    hosted_domain  varchar(255),
    linked_at      timestamptz  not null default now(),
    last_login_at  timestamptz,
    constraint chk_user_identities_provider check (provider in ('GOOGLE')),
    constraint uq_user_identities_provider_subject unique (provider, subject),
    constraint uq_user_identities_provider_user unique (provider, user_id)
);

create index idx_user_identities_user on user_identities (user_id);

comment on table user_identities is
    '외부 제공자 로그인 연동. provider+subject가 조인 키이고 email_at_link는 연동 시점 기록(동기화하지 않는다).';

-- The cascade above never fires on withdrawal: 탈퇴 deletes no user row, it
-- sets status = WITHDRAWN. AccountService.withdraw therefore deletes these rows
-- explicitly — without that, a withdrawn address keeps a live `sub` and the
-- next Google login walks straight back into the withdrawn account.

-- In-flight authorization-code exchanges. The console never holds these values:
-- it carries only the opaque `state` back from Google and posts it, so the
-- nonce and the PKCE verifier stay server-side and there is no cookie to make
-- SameSite=Strict refuse to send across the Google redirect.
--
-- Same shape as email_verifications and mfa_login_tokens: the token is stored
-- hashed and consumed by a conditional UPDATE, so exactly one concurrent caller
-- wins and a replay answers 410 rather than a second login.
create table oauth_flows (
    id                  bigserial primary key,
    state_hash          varchar(64)  not null unique,
    nonce               varchar(64)  not null,
    code_verifier       varchar(128) not null,
    purpose             varchar(20)  not null,
    -- Set for the purposes that act on an existing session (REVERIFY, LINK).
    -- The callback refuses a flow whose initiator is not the account the
    -- verified subject resolves to, so a flow cannot be finished by anyone else.
    initiating_user_id  bigint       references users (id) on delete cascade,
    -- Echoed back to the console so it can return the user where they were.
    -- Validated as an internal path before use; never used to build a redirect
    -- server-side, because this API issues none.
    redirect_to         varchar(512),
    created_at          timestamptz  not null default now(),
    expires_at          timestamptz  not null,
    consumed_at         timestamptz,
    constraint chk_oauth_flows_purpose check (purpose in ('LOGIN', 'REVERIFY', 'LINK'))
);

create index idx_oauth_flows_expires on oauth_flows (expires_at);

-- A verified Google identity with no account yet. Deliberately not an account:
-- creating the user here would leave a row that has agreed to nothing, and the
-- signup transaction's rule is that an incomplete consent set rolls the user
-- back. The console redeems this for the onboarding form and posts consents
-- with it, so the same invariant holds on the social path.
create table oauth_registrations (
    id             bigserial primary key,
    token_hash     varchar(64)  not null unique,
    provider       varchar(20)  not null,
    subject        varchar(255) not null,
    email          citext       not null,
    name           varchar(100),
    hosted_domain  varchar(255),
    created_at     timestamptz  not null default now(),
    expires_at     timestamptz  not null,
    consumed_at    timestamptz,
    constraint chk_oauth_registrations_provider check (provider in ('GOOGLE'))
);

create index idx_oauth_registrations_expires on oauth_registrations (expires_at);

comment on table oauth_flows is
    '진행 중인 OAuth 인가 교환. state 는 해시로만 저장하고 단회 소비한다.';
comment on table oauth_registrations is
    '검증된 외부 신원 중 아직 계정이 없는 것. 온보딩 폼이 이 토큰으로 약관 동의와 함께 계정을 만든다.';
