-- Who in an organisation is mailed about new requests, and which mails wait
-- to be sent together.
--
-- Schema only. Both columns default to false, which keeps today's behaviour
-- for every existing row: with nobody chosen, every org admin is mailed, and a
-- row that is not marked is sent on the next dispatch as before.
--
-- user_org_roles.request_mail sits on the role row, so revoking the role takes
-- the choice with it. Only the roles that may approve a request can hold it.
alter table user_org_roles
    add column request_mail boolean not null default false;
alter table user_org_roles
    add constraint chk_user_org_roles_request_mail
        check (not request_mail or role::text in ('ORG_ADMIN', 'ORG_MANAGER'));

comment on column user_org_roles.request_mail is
    '이 기관의 신청 접수 메일을 받는 담당자인지. 기관에 담당자가 한 명도 없으면 기관 관리자 전원이 받는다.';

-- notifications.bundle marks a mail addressed to an administrator. The
-- dispatcher sends one such mail per recipient per window and folds the rest
-- into a single mail; the inbox row itself is visible at once either way.
alter table notifications
    add column bundle boolean not null default false;

-- The dispatcher's "when did this recipient last get one" lookup.
create index notifications_bundle_sent_idx
    on notifications (user_id, sent_at) where bundle;

comment on column notifications.bundle is
    '관리자에게 가는 메일이라 발송 창 안에서 다른 메일과 묶어 한 통으로 보낼 수 있는지. 알림함 표시와는 무관하다.';
