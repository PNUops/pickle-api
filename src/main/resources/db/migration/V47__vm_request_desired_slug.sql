-- 계약 v0.12.0 (VM 슬러그 사용자 지정): 신청자가 희망하는 호스트명(SSH 접속명·
-- 슬러그)을 신청서에 기록한다. 승인 시 관리자가 grantedSlug로 수락·변경하고
-- null/공백이면 기존처럼 자동 생성(그룹 슬러그 + 랜덤 4자)한다. 패턴·예약어·
-- 중복(파기 VM 포함) 검증은 애플리케이션 계층(VmSlugPolicy)에서 수행하며,
-- 최종 유일성 백스톱은 vms.hostname unique 제약이다.
alter table vm_requests
    add column desired_slug text;

comment on column vm_requests.desired_slug is
    'Requester-desired hostname/slug (contract CreateVmRequest.desiredSlug, v0.12.0); null = auto-generate at approval.';
