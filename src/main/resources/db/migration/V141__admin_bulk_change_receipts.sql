-- Saved previews and committed receipts share the existing bulk executor.
create table admin_bulk_change_previews (
    id uuid primary key,
    actor_id bigint not null references users(id),
    target_type text not null,
    change_kind text not null,
    request jsonb not null,
    preview jsonb not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    constraint admin_bulk_preview_expiry_check check (expires_at > created_at)
);
create index admin_bulk_preview_actor_time on admin_bulk_change_previews(actor_id, created_at desc);
comment on table admin_bulk_change_previews is '관리자 일괄 변경 미리보기. 새 적용은 저장 뒤 24시간까지만 허용하며 자원 변경을 예약하지 않습니다.';

create table admin_bulk_change_operations (
    id uuid primary key,
    preview_id uuid not null unique references admin_bulk_change_previews(id),
    actor_id bigint not null references users(id),
    binding text not null,
    result jsonb,
    created_at timestamptz not null,
    committed_at timestamptz,
    constraint admin_bulk_operation_commit_check check ((result is null) = (committed_at is null))
);
create index admin_bulk_operation_actor_time on admin_bulk_change_operations(actor_id, created_at desc);
comment on table admin_bulk_change_operations is '한 미리보기에 한 적용만 결합하는 결과 영수증. 자원 변경과 결과는 같은 트랜잭션에서 저장하며 재조회는 작업을 재실행하지 않습니다.';

create table admin_bulk_change_effects (
    id uuid primary key,
    operation_id uuid not null references admin_bulk_change_operations(id),
    target_id uuid,
    kind text not null,
    label text not null,
    outcome text not null,
    external_reference text,
    registered_at timestamptz not null,
    observed_at timestamptz,
    constraint admin_bulk_effect_kind_check check (kind in ('AUDIT', 'QUEUE', 'EXTERNAL_CALLBACK', 'CALLBACK')),
    constraint admin_bulk_effect_outcome_check check (outcome in ('REGISTERED', 'ACCEPTED', 'COMPLETED', 'FAILED', 'UNKNOWN'))
);
create index admin_bulk_effect_operation on admin_bulk_change_effects(operation_id, registered_at, id);
comment on table admin_bulk_change_effects is '기존 커밋 후 콜백의 등록·큐 접수·실패 관측. REGISTERED는 접수 성공이나 외부 완료를 증명하지 않으며 재실행 수단을 제공하지 않습니다.';
