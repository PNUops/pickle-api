-- 공지사항: both visibility axes leave the notice board, and popup takes over.
--
-- V92 gave a notice two axes. scope (PLATFORM/ORG) said whose notice it was,
-- and audience (PUBLIC/USERS) said how far it reached. Neither survives.
--
-- scope goes because the product does not work that way: an organisation names
-- who supplies a node or a resource, and it is not a mechanism for deciding who
-- may use a feature. Every notice is platform-wide and every administrator
-- publishes to everyone.
--
-- audience goes because it duplicated a decision the author was already making.
-- popup — until now only "does the console raise this as a modal" — becomes the
-- single flag: a notice worth interrupting a reader for is a notice worth
-- showing a visitor who cannot sign in, which is what an outage notice is. So
-- popup = true means both, and popup = false means the notice sits on the board
-- for signed-in readers.
--
-- The coupling is deliberate and it is a real loss: there is no longer any way
-- to raise a modal for signed-in users only. That combination is gone, not
-- merely unreachable.
--
-- Nothing is migrated because there is nothing to migrate: notices and
-- notice_images were both empty when this was written.
--
-- Dropping a column takes the CHECKs on it with it, so each drop constraint
-- below is redundant as DDL. They are written out anyway: which invariant was
-- retired is what this file is a record of, and one of them is a security
-- statement rather than a bookkeeping one.

alter table notices drop constraint if exists notices_scope_check;
alter table notices drop constraint if exists notices_scope_target_check;
alter table notices drop constraint if exists notices_audience_check;
-- This one kept an organisation's notice away from anonymous readers, and V92
-- put it in the database precisely because the consequence of getting it wrong
-- is disclosure. Retiring it widens who may place text and images in front of
-- an anonymous visitor from the system administrators to every administrator:
-- a decision, recorded here so it is not later read as an accident. What
-- separates anonymous from signed-in readers from now on is popup = true plus
-- the publication window, and that pair is the whole of the boundary.
alter table notices drop constraint if exists notices_public_is_platform_check;

-- The index existed to serve the ORG-tier admin list and the authenticated
-- reader's own-org rows. Neither query survives.
drop index if exists notices_org_id_idx;

alter table notices drop column if exists org_id;
alter table notices drop column if exists scope;
alter table notices drop column if exists audience;

-- Every comment here argued from one of the two dropped axes, so leaving them
-- would leave the table describing columns that no longer exist. Rewritten
-- rather than deleted: the table still needs to say what it is, how it differs
-- from an announcement, and which column now decides who may read it.
comment on table notices is
    '공지사항 문서. 발송이 아니라 게시물이며, 알림 행을 만들지 않는다. 모든 공지는 전체 사용자 대상이고, popup 하나가 모달 표시와 비로그인 노출을 함께 정한다.';
comment on column notices.popup is
    'true면 콘솔이 모달로 띄우고 비로그인 방문자에게도 보인다. false면 로그인한 사용자만 게시판에서 읽는다. 게시 기간과 함께 익명 열람 경계를 이루는 유일한 값이다.';
comment on column notices.starts_at is
    '게시 시작 시각. starts_at <= now() < ends_at인 동안만 공개 목록에 나타나며, 관리 목록은 창 밖의 행도 함께 보여 준다.';
