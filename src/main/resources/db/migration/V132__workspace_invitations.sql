-- Workspace invitations: an owner names people by email or 학번, including
-- people who have not signed up yet. An existing ACTIVE account is added
-- straight away and never gets a row here; everyone else gets a PENDING row
-- that turns into a membership when the account that matches it becomes
-- usable (activation for an email, the first stored 학번 for a 학번).
--
-- There is no expiry column. Invitations do not expire: the owner cancels one
-- that should no longer stand. A deadline would only move that decision to a
-- clock that knows nothing about the course or project it belongs to.

create table workspace_invitations (
    id                 bigint generated always as identity primary key,
    public_id          uuid        not null unique default gen_random_uuid(),
    workspace_id       bigint      not null references workspaces (id),
    invitee_email      citext,
    invitee_student_no varchar(20),
    role               workspace_member_role not null,
    status             text        not null default 'PENDING',
    invited_by         bigint      not null references users (id),
    accepted_user_id   bigint      references users (id),
    accepted_at        timestamptz,
    canceled_by        bigint      references users (id),
    canceled_at        timestamptz,
    created_at         timestamptz not null default now(),

    -- An invitation names one person one way. Matching on both would leave
    -- no answer when the email and the 학번 belong to different accounts.
    constraint workspace_invitations_one_invitee
        check ((invitee_email is null) <> (invitee_student_no is null)),
    constraint workspace_invitations_status_known
        check (status in ('PENDING', 'ACCEPTED', 'CANCELED')),
    -- Acceptance names who accepted and when, together or not at all.
    constraint workspace_invitations_accepted_pair
        check ((accepted_user_id is null) = (accepted_at is null)),
    constraint workspace_invitations_canceled_pair
        check ((canceled_by is null) = (canceled_at is null)),
    constraint workspace_invitations_accepted_has_acceptor
        check (status <> 'ACCEPTED' or accepted_user_id is not null),
    constraint workspace_invitations_canceled_has_canceler
        check (status <> 'CANCELED' or canceled_by is not null)
);

-- One open invitation per person per workspace. Answered and canceled rows
-- stay as history and do not block a fresh invitation.
create unique index workspace_invitations_one_pending_email
    on workspace_invitations (workspace_id, lower(invitee_email))
    where status = 'PENDING' and invitee_email is not null;
create unique index workspace_invitations_one_pending_student_no
    on workspace_invitations (workspace_id, upper(invitee_student_no))
    where status = 'PENDING' and invitee_student_no is not null;

-- Claim lookups: every open invitation for one address or one 학번, across
-- workspaces. Case-folded the same way users.email (citext) and the
-- users.student_no index (upper, V131) are.
create index workspace_invitations_pending_email_idx
    on workspace_invitations (lower(invitee_email))
    where status = 'PENDING';
create index workspace_invitations_pending_student_no_idx
    on workspace_invitations (upper(invitee_student_no))
    where status = 'PENDING';

-- The owner's list reads one workspace's open rows.
create index workspace_invitations_workspace_idx
    on workspace_invitations (workspace_id, created_at)
    where status = 'PENDING';

comment on table workspace_invitations is
    '워크스페이스 초대. 가입하지 않은 사람도 이메일이나 학번으로 초대하며, 해당 계정이 활성화되거나 학번을 처음 저장하면 구성원이 된다. 만료 없음(소유자가 취소한다).';
comment on column workspace_invitations.status is
    'PENDING: 대기 중. ACCEPTED: 구성원이 되어 끝남(이미 구성원이었던 경우 포함). CANCELED: 소유자가 취소함. 구성원에서 제거되어도 ACCEPTED는 다시 열리지 않는다.';
comment on column workspace_invitations.accepted_user_id is
    '초대를 받아 구성원이 된 계정. null이면 아직 아무 계정도 받지 않았다(PENDING 또는 받기 전에 취소됨).';
comment on column workspace_invitations.role is
    '초대할 때 소유자가 정한 역할. 지금은 MEMBER만 발급한다.';
