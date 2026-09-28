-- The people a request asks resources for, when it asks for more than its
-- requester. Schema only: rows are written by the request flow.
--
-- A request with no rows here is the ordinary single-person request, whose
-- requester becomes the owner of what approval creates. A request with rows
-- creates one resource per row, owned by that row's person, and never one for
-- the requester.
--
-- A row starts as either a user (an ACTIVE member when it was named) or an
-- invitation (a PENDING invitation into the same workspace). An invitation row
-- keeps invitation_id after the invitee joins and user_id is filled in, so
-- both columns stay readable for the history of the row.
create table request_recipients (
    id            bigint generated always as identity primary key,
    public_id     uuid        not null unique,
    request_id    bigint      not null references requests (id),
    user_id       bigint      references users (id),
    invitation_id bigint      references workspace_invitations (id),
    status        text        not null,
    resource_id   bigint,
    reason        text,
    attempts      int         not null default 0,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    constraint request_recipients_subject_ck
        check (user_id is not null or invitation_id is not null),
    constraint request_recipients_status_ck
        check (status in ('PENDING_JOIN', 'QUEUED', 'CREATING', 'CREATED',
                          'SKIPPED_EXPIRED', 'SKIPPED_INELIGIBLE', 'FAILED', 'CANCELED')),
    constraint request_recipients_attempts_ck check (attempts >= 0)
);

create unique index request_recipients_request_user_uq
    on request_recipients (request_id, user_id) where user_id is not null;
create unique index request_recipients_request_invitation_uq
    on request_recipients (request_id, invitation_id) where invitation_id is not null;
create index request_recipients_request_idx on request_recipients (request_id);
-- The materializer's work list.
create index request_recipients_queued_idx
    on request_recipients (id) where status = 'QUEUED';
-- The claim hook's lookup when an invitee joins.
create index request_recipients_pending_join_idx
    on request_recipients (invitation_id) where status = 'PENDING_JOIN';

comment on table request_recipients is
    '한 신청으로 여러 사람에게 리소스를 만들 때의 대상자. 행이 없는 신청은 신청자 본인이 소유자가 되는 일반 신청이다.';
comment on column request_recipients.user_id is
    '대상 계정. 초대로 지정한 대상은 가입해 구성원이 된 뒤에 채워진다.';
comment on column request_recipients.invitation_id is
    '가입 전 대상자를 가리킨 워크스페이스 초대. 가입한 뒤에도 남긴다.';
comment on column request_recipients.status is
    'PENDING_JOIN=가입 대기(승인됐지만 아직 가입하지 않음), QUEUED=생성 대기, CREATING=생성 중, '
    'CREATED=생성됨(resource_id가 만든 리소스), SKIPPED_EXPIRED=가입 시점에 사용 기간이 끝나 만들지 않음, '
    'SKIPPED_INELIGIBLE=더 이상 대상이 아님(구성원이 아니거나 초대가 취소됨), FAILED=생성 실패(다시 시도 가능), '
    'CANCELED=신청이 취소되거나 반려됨';
comment on column request_recipients.resource_id is
    '만든 리소스의 내부 id. 종류는 신청의 resource_type이 정한다.';
comment on column request_recipients.reason is
    '만들지 않았거나 실패한 이유. 사용자에게 보이는 문장이다.';
comment on column request_recipients.attempts is
    '생성을 시도했다가 실패한 횟수.';
