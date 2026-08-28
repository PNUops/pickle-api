-- 공지사항: the organisation axis leaves the notice board.
--
-- V92 gave a notice two axes — scope (PLATFORM/ORG) and audience
-- (PUBLIC/USERS) — so that an organisation could address its own people. The
-- product does not work that way: an organisation names who supplies a node or
-- a resource, and it is not a mechanism for deciding who may use a feature.
-- Every notice is therefore platform-wide, an organisation administrator
-- publishes to everyone, and audience alone decides how far a notice reaches.
--
-- Nothing is migrated because there is nothing to migrate: notices and
-- notice_images were both empty when this was written.
--
-- The column drop takes the CHECKs on it with it, so each drop constraint
-- below is redundant as DDL. They are written out anyway: which invariant was
-- retired is what this file is a record of, and one of them is a security
-- statement rather than a bookkeeping one.

alter table notices drop constraint if exists notices_scope_check;
alter table notices drop constraint if exists notices_scope_target_check;
-- This one kept an organisation's notice away from anonymous readers, and V92
-- put it in the database precisely because the consequence of getting it wrong
-- is disclosure. Retiring it widens who may place text and images in front of
-- an anonymous visitor from the system administrators to every administrator:
-- a decision, recorded here so it is not later read as an accident. What still
-- separates anonymous from signed-in readers is audience = 'PUBLIC' plus the
-- publication window, and that pair is now the whole of the boundary.
alter table notices drop constraint if exists notices_public_is_platform_check;

-- The index existed to serve the ORG-tier admin list and the authenticated
-- reader's own-org rows. Neither query survives.
drop index if exists notices_org_id_idx;

alter table notices drop column if exists org_id;
alter table notices drop column if exists scope;

-- Both comments argued from the scope axis, so dropping the columns leaves
-- them describing objects that no longer exist. Rewritten rather than deleted:
-- the table still needs to say what it is and how it differs from an
-- announcement.
comment on table notices is
    '공지사항 문서. 발송이 아니라 게시물이며, 알림 행을 만들지 않는다. 모든 공지는 전체 사용자 대상이고, audience(PUBLIC/USERS)만이 비로그인 방문자에게까지 보이는지를 정한다.';
comment on column notices.audience is
    'PUBLIC이면 로그인 없이도 보이고 USERS면 로그인해야 보인다. 게시 기간과 함께 익명 열람 경계를 이루는 유일한 값이다.';
