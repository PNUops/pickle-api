-- 공지사항: the platform's own notice board, and deliberately not the
-- announcements table (V16). An announcement is a SEND — it snapshots a
-- recipient set into per-user notifications rows and reaches an inbox. A notice
-- is a DOCUMENT that sits on a page until it expires; nobody is notified, and
-- the same row is read by everyone who opens the page.
--
-- Two axes decide who may open it:
--   scope    — PLATFORM (전역) or ORG (기관): whose notice this is;
--   audience — PUBLIC (랜딩·익명까지) or USERS (로그인 후에만): how far it reaches.
--
-- They are not independent. An organisation's internal notice must never be
-- readable anonymously, so audience is pinned to USERS whenever scope is ORG.
-- That is the third CHECK below, written as an implication rather than as a
-- pairing because a PLATFORM notice is legitimately either.
--
-- Schema only. A notice is content an administrator writes through the API; it
-- is neither this host's inventory nor a configuration key, so nothing is
-- inserted here.

create table notices (
    id         bigint       generated always as identity primary key,
    public_id  uuid         not null default gen_random_uuid(),
    title      varchar(200) not null,
    body       text         not null,
    scope      varchar(16)  not null,
    -- The owning organisation, present exactly when scope is ORG.
    org_id     bigint       references orgs (id),
    audience   varchar(16)  not null,
    -- Display order inside the active window: pinned rows sort first.
    pinned     boolean      not null default false,
    -- Whether the console additionally raises the notice as a modal.
    popup      boolean      not null default false,
    -- The active window. starts_at defaults to now() so an ordinary notice is
    -- live the moment it is saved; a null ends_at means it never expires.
    starts_at  timestamptz  not null default now(),
    ends_at    timestamptz,
    created_by bigint       not null references users (id),
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now(),
    constraint notices_scope_check check (scope in ('PLATFORM', 'ORG')),
    constraint notices_audience_check check (audience in ('PUBLIC', 'USERS')),
    constraint notices_scope_target_check check (
        (scope = 'PLATFORM' and org_id is null) or
        (scope = 'ORG' and org_id is not null)
    ),
    constraint notices_window_check check (ends_at is null or ends_at > starts_at),
    -- An ORG notice is never PUBLIC. The database carries this rather than the
    -- service alone because the consequence of getting it wrong is disclosure
    -- to anonymous readers, and a row written by any other path (a repair, a
    -- future admin tool) must fail the same way.
    constraint notices_public_is_platform_check check (audience = 'USERS' or scope = 'PLATFORM')
);

create unique index notices_public_id_uidx on notices (public_id);
-- The public list reads one window at a time; the org index serves the ORG-tier
-- admin list and the authenticated reader's own-org rows.
create index notices_window_idx on notices (starts_at, ends_at);
create index notices_org_id_idx on notices (org_id);

comment on table notices is
    '공지사항 문서. 발송이 아니라 게시물이며, 알림 행을 만들지 않는다. scope(PLATFORM/ORG)와 audience(PUBLIC/USERS)가 열람 범위를 정하고, ORG 공지는 제약으로 언제나 USERS다.';
comment on column notices.audience is
    'PUBLIC이면 로그인 없이도 보이고 USERS면 로그인해야 보인다. scope=ORG인 행은 PUBLIC일 수 없다.';
comment on column notices.starts_at is
    '게시 시작 시각. starts_at <= now() < ends_at인 동안만 공개 목록에 나타나며, 관리 목록은 창 밖의 행도 함께 보여 준다.';

-- Images belong to the notice body. The bytes live in the row for now; the
-- service reaches them through a storage interface so moving them to a file or
-- object store later is one class, not a rewrite of the notice paths.
create table notice_images (
    id           bigint       generated always as identity primary key,
    public_id    uuid         not null default gen_random_uuid(),
    notice_id    bigint       not null references notices (id) on delete cascade,
    -- What the uploader called the file; absent when the client sent no name.
    file_name    varchar(255),
    -- The type the bytes actually are, determined by reading their leading
    -- bytes at upload. The client's declared Content-Type is never stored.
    content_type varchar(64)  not null,
    byte_size    integer      not null,
    data         bytea        not null,
    sort_order   integer      not null default 0,
    created_at   timestamptz  not null default now(),
    constraint notice_images_byte_size_check check (byte_size > 0 and byte_size <= 2097152)
);

create unique index notice_images_public_id_uidx on notice_images (public_id);
create index notice_images_notice_id_idx on notice_images (notice_id);

comment on table notice_images is
    '공지 본문 이미지. 바이트를 행에 담지만 서비스는 저장소 인터페이스를 거쳐 읽고 쓰므로 나중에 파일·오브젝트 스토리지로 옮길 때 이 테이블 밖은 바뀌지 않는다.';
comment on column notice_images.content_type is
    '업로드 시 선두 바이트로 판별한 실제 형식. 클라이언트가 선언한 Content-Type은 저장하지 않는다.';
comment on column notice_images.byte_size is
    '바이트 수. 한 장 2 MiB 상한은 CHECK로 걸려 있으며, 한 공지당 장수 상한은 서비스가 강제한다.';
