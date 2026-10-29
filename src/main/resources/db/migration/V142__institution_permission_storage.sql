-- Storage foundation only. Existing role writers and authorization are unchanged.
-- No existing role row is converted, and no absent payload grants authority.
create sequence institution_permission_revision_seq as bigint minvalue 1 no cycle;

alter table user_org_roles
    add column permission_mode varchar(8),
    add column permission_payload jsonb,
    add column permission_revision bigint;

alter table user_org_roles
    add constraint chk_user_org_roles_permission_state
        check (num_nonnulls(permission_mode, permission_payload, permission_revision) in (0, 3)),
    add constraint chk_user_org_roles_permission_mode
        check (permission_mode in ('FULL', 'SELECTED')),
    add constraint chk_user_org_roles_permission_payload
        check (jsonb_typeof(permission_payload) = 'object'),
    add constraint chk_user_org_roles_permission_revision
        check (permission_revision > 0);

comment on sequence institution_permission_revision_seq is
    '명시 기관 권한 저장의 새 버전. 역할 행 삭제·재부여와 트랜잭션 rollback 이후에도 사용한 값을 재사용하지 않음';
comment on column user_org_roles.permission_mode is
    '명시 기관 권한 FULL 또는 SELECTED. 세 permission 열이 모두 NULL이면 미전환이며 새 권한 reader는 거절';
comment on column user_org_roles.permission_payload is
    '고정 업무 ID 목록과 그룹별 ALL/LISTED 범위. workspace 공개 UUID를 보존하며 삭제된 ID를 전체 범위로 바꾸지 않음';
comment on column user_org_roles.permission_revision is
    'sequence로 발급한 기관별 권한 원천 버전. 명단·수신 설정의 orgs.admin_revision과 별도';
