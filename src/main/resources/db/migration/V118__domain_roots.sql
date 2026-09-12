-- The roots this platform issues names under, as rows rather than as a setting.
--
-- Until now a root was a string in settings.allowed_root_domains and nothing
-- else: enough to answer "may a name be issued here", and not enough to answer
-- anything about the root itself. The question that forced the change is whose
-- the root is. A name issued on its own has no VM and no request behind it, so
-- there is nothing on its path carrying an organisation, and without one the
-- row is invisible to every organisation administrator and visible only to a
-- system administrator -- the wrong shape for the one resource kind whose
-- content this platform does not control.
--
-- Asking the person to pick an organisation was the other way to answer it, and
-- it is worse: it asks somebody issuing a name for their own site to know an
-- administrative fact about the platform, and nothing they could answer would
-- be more true than what the root already implies.
--
-- The settings key stays. It says which roots may be issued under, which is a
-- policy switch an administrator flips; this table says what a root IS. A root
-- listed in the setting with no row here cannot be issued under, which is the
-- fail-closed direction.

create table domain_roots (
    id          bigint generated always as identity primary key,
    -- The DNS name of the root, lower case, no trailing dot. Unique because a
    -- root is one thing: two rows would make "whose is it" have two answers.
    root_domain text not null unique,
    org_id      bigint not null references orgs (id),
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

alter table domain_roots
    add constraint domain_roots_root_domain_check
        check (root_domain = lower(root_domain) and root_domain not like '%.');

create index domain_roots_org_id_idx on domain_roots (org_id);

comment on table domain_roots is
    '이름을 발급할 수 있는 루트 도메인과 그 소유 기관. 발급된 이름의 기관은 여기서 온다.';
comment on column domain_roots.org_id is
    '이 루트 아래 발급된 이름이 속하는 기관. 관리자 목록의 기관 범위가 이 값을 따른다.';

-- No rows here. Which roots exist is environment, not schema: a development
-- database gets its row from the development seeder and the platform host gets
-- its own from the infra script that already writes the settings it belongs
-- with. A migration that inserted one would put this machine's addressing into
-- every other machine's history.
