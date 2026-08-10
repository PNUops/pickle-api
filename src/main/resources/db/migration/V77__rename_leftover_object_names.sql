-- Finishes the renaming V71 said it had finished, and corrects three statements
-- earlier migrations make about themselves.
--
-- V71 renamed the tables, columns, types and the indexes and constraints whose
-- names appear in code, and claimed "every name a later migration or an error
-- message could quote is renamed too". That was not true. Postgres derives names
-- for things nobody types -- NOT NULL constraints, identity sequences, a foreign
-- key created before its table was renamed -- and those keep the old word.
--
-- Where they surface differs, and it is worth being exact because the first
-- draft of this comment was wrong. A foreign key violation quotes its own name,
-- so vms_template_id_fkey and groups_deleted_by_fkey reach an operator verbatim.
-- A NOT NULL violation does not: measured on a copy of the dev database, it
-- names the relation and the column and never the constraint. Those names
-- surface instead in pg_dump output, in \d, and in any later migration that has
-- to drop or alter one by name -- which is the moment a name for a table that no
-- longer exists costs somebody an hour.
--
-- Two names here predate the workspace round and are fixed in the same pass,
-- because they are the same defect: os_images kept vm_templates_id_seq and
-- vms.image_id kept vms_template_id_fkey when V62 renamed that table.

-- ── workspaces (was groups) ───────────────────────────────────────────────
alter table workspaces rename constraint groups_deleted_by_fkey to workspaces_deleted_by_fkey;
alter table workspaces rename constraint groups_created_at_not_null to workspaces_created_at_not_null;
alter table workspaces rename constraint groups_id_not_null to workspaces_id_not_null;
alter table workspaces rename constraint groups_kind_not_null to workspaces_kind_not_null;
alter table workspaces rename constraint groups_name_not_null to workspaces_name_not_null;
alter table workspaces rename constraint groups_updated_at_not_null to workspaces_updated_at_not_null;

-- ── workspace_members (was group_members; group_id became workspace_id) ───
alter table workspace_members rename constraint group_members_created_at_not_null to workspace_members_created_at_not_null;
alter table workspace_members rename constraint group_members_group_id_not_null to workspace_members_workspace_id_not_null;
alter table workspace_members rename constraint group_members_id_not_null to workspace_members_id_not_null;
alter table workspace_members rename constraint group_members_role_not_null to workspace_members_role_not_null;
alter table workspace_members rename constraint group_members_updated_at_not_null to workspace_members_updated_at_not_null;
alter table workspace_members rename constraint group_members_user_id_not_null to workspace_members_user_id_not_null;

-- ── requests (was vm_requests; group_id became workspace_id) ──────────────
alter table requests rename constraint vm_requests_created_at_not_null to requests_created_at_not_null;
alter table requests rename constraint vm_requests_group_id_not_null to requests_workspace_id_not_null;
alter table requests rename constraint vm_requests_id_not_null to requests_id_not_null;
alter table requests rename constraint vm_requests_org_id_not_null to requests_org_id_not_null;
alter table requests rename constraint vm_requests_purpose_not_null to requests_purpose_not_null;
alter table requests rename constraint vm_requests_requester_id_not_null to requests_requester_id_not_null;
alter table requests rename constraint vm_requests_status_not_null to requests_status_not_null;
alter table requests rename constraint vm_requests_updated_at_not_null to requests_updated_at_not_null;

-- ── request_reviews (was vm_request_reviews) ─────────────────────────────
alter table request_reviews rename constraint vm_request_reviews_created_at_not_null to request_reviews_created_at_not_null;
alter table request_reviews rename constraint vm_request_reviews_decision_not_null to request_reviews_decision_not_null;
alter table request_reviews rename constraint vm_request_reviews_id_not_null to request_reviews_id_not_null;
alter table request_reviews rename constraint vm_request_reviews_request_id_not_null to request_reviews_request_id_not_null;
alter table request_reviews rename constraint vm_request_reviews_reviewer_id_not_null to request_reviews_reviewer_id_not_null;
alter table request_reviews rename constraint vm_request_reviews_updated_at_not_null to request_reviews_updated_at_not_null;

-- ── identity sequences ───────────────────────────────────────────────────
alter sequence groups_id_seq rename to workspaces_id_seq;
alter sequence group_members_id_seq rename to workspace_members_id_seq;
alter sequence vm_requests_id_seq rename to requests_id_seq;
alter sequence vm_request_reviews_id_seq rename to request_reviews_id_seq;
alter sequence vm_templates_id_seq rename to os_images_id_seq;

-- ── the foreign key V62 left behind ──────────────────────────────────────
alter table vms rename constraint vms_template_id_fkey to vms_image_id_fkey;

-- ── corrections to what earlier migrations say ───────────────────────────
-- Those files are applied everywhere and cannot be edited: Flyway checksums
-- them, and editing one after deployment took the api down on 2026-08-10.
-- A wrong statement in an applied migration is corrected by a later one, and
-- the correction goes where the database itself will show it.

comment on column requests.display_name is
    'VM 표시명 — 신청자가 신청서에서 정한다. V73이 "신청자의 표시명"이라 적었지만 사용자 이름이 아니라 리소스 이름이다. 호스트명 씨앗도 이 값이며, 비어 있거나 슬러그 정책에 걸리면 워크스페이스 식별자로 대체한다. 종류가 늘면 이 컬럼의 의미를 종류별 세부 테이블로 내릴지 정해야 한다.';

comment on constraint chk_vm_request_details_approved_granted on vm_request_details is
    '부여 사양은 전부 있거나 전부 없어야 한다. V74 머리말은 이 검사가 "부분 사양만 막는다"고 적었으나 실제로는 granted_vcpu만 비어 있는 형태를 통과시킨다 — 그 앵커에 걸리기 때문이다. "승인이면 반드시 있다"와 함께 지연 제약 트리거가 지킨다.';

comment on table announcements is
    '공지. 범위는 ALL/ORG/WORKSPACE. V71 머리말은 이 표의 스코프 라벨 두 건을 고쳐 쓴다고 적었지만, 그 시점에 GROUP 스코프 행은 하나도 없어 UPDATE는 0행을 썼다. 그 파일이 환경을 서술하는 행을 넣지 않는다는 것 자체는 사실이다.';
