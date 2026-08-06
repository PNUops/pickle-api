-- Notifications & announcements (contract v0.5.0).
-- Expand-only: new enums/tables/indexes plus settings seeds; no existing
-- object is altered structurally.

create type notification_channel as enum ('EMAIL');
create type notification_status as enum ('PENDING', 'SENT', 'FAILED', 'SKIPPED');

-- ── announcements: one row per admin broadcast; recipients get individual
--    notifications rows (fan-out snapshot at send time). ──
create table announcements (
    id              bigint generated always as identity primary key,
    author_id       bigint not null references users (id),
    scope           text not null check (scope in ('ALL', 'ORG', 'GROUP')),
    org_id          bigint references orgs (id),
    group_id        bigint references groups (id),
    title           text not null,
    body            text not null,
    recipient_count int not null default 0,
    created_at      timestamptz not null default now(),
    -- scope pins its target columns: ALL = broadcast (no target), ORG = org
    -- only, GROUP = group only (the sender org is not a column — visibility
    -- follows the author's org via users.org_id).
    constraint announcements_scope_target_chk check (
        (scope = 'ALL'   and org_id is null     and group_id is null) or
        (scope = 'ORG'   and org_id is not null and group_id is null) or
        (scope = 'GROUP' and group_id is not null and org_id is null)
    )
);
create index announcements_org_id_idx on announcements (org_id);

-- ── notifications: the per-user inbox row IS the email delivery log
--    (single table, channel EMAIL in v1). Titles/bodies are
--    rendered Korean at publish time; payload carries whitelisted display
--    fields only — never tokens or passwords. ──
create table notifications (
    id              bigint generated always as identity primary key,
    user_id         bigint not null references users (id),
    event           text not null,              -- dot-namespaced catalog id
    title           text not null,
    body            text not null,
    link_path       text,                       -- console-relative, nullable
    importance      text not null default 'NORMAL' check (importance in ('NORMAL', 'HIGH')),
    payload         jsonb,
    dedup_key       text,                       -- per-user idempotency guard
    announcement_id bigint references announcements (id),
    channel         notification_channel not null default 'EMAIL',
    status          notification_status not null default 'PENDING',
    attempts        int not null default 0,
    last_error      text,
    next_attempt_at timestamptz not null default now(),
    sent_at         timestamptz,
    read_at         timestamptz,
    created_at      timestamptz not null default now()
);

create index notifications_user_created_idx on notifications (user_id, created_at desc);
create index notifications_user_unread_idx on notifications (user_id) where read_at is null;
create index notifications_pending_due_idx on notifications (next_attempt_at) where status = 'PENDING';
create index notifications_status_created_idx on notifications (status, created_at);
create unique index notifications_user_dedup_uq on notifications (user_id, dedup_key)
    where dedup_key is not null;

-- No settings seed here. notification_retention_days and vm_expiry_notice_days
-- are retention and notice-timing policy an operator sets, so an operator
-- bootstrap script writes them and the dev/test seeder supplies equivalents.

-- ── Console settings screen shows description as-is (contract v0.5.0:
--    SettingView.description is Korean). These relabels now find nothing on a
--    database built from these files, since the rows they targeted are written
--    with their Korean text by whoever creates them; they are kept because the
--    already-migrated database still carries the English text they replace. ──
update settings set description = 'VM 신청에서 선택할 수 있는 루트 도메인 목록.'
 where key = 'allowed_root_domains';
update settings set description = '신청할 수 없는 예약 서브도메인 목록.'
 where key = 'reserved_subdomains';
update settings set description = '서브도메인 금칙어(욕설·사칭) 목록. 관리자가 확장할 수 있습니다.'
 where key = 'profanity_subdomains';
update settings set description = '승인 화면 경고 임계값 — 할당 vCPU / 물리 스레드 비율.'
 where key = 'vcpu_overcommit_warn';
update settings set description = '승인 화면 경고 임계값 — 할당 메모리 / 물리 메모리 비율.'
 where key = 'memory_usage_warn';
update settings set description = '회수된 IP를 재할당하지 않고 격리하는 시간(시간).'
 where key = 'ip_quarantine_hours';
update settings set description = '셀프 삭제 접수 후 물리 파기까지의 유예 시간(시간).'
 where key = 'vm_delete_grace_hours';
update settings set description = '관리자 예약 삭제가 보장해야 하는 최소 사전 통보 기간(일).'
 where key = 'admin_delete_min_notice_days';
update settings set description = 'SSH 게이트웨이 전체 활성화 (킬 스위치). false면 모든 SSH 접속이 차단됩니다.'
 where key = 'ssh_gateway_enabled';
