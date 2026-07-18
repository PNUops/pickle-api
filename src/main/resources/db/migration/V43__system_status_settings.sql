-- M6 점검 모드·공지 배너·문의처 (docs/product-spec §운영, 계약 v0.9.0
-- GET /meta/status). 운영자가 PUT /admin/settings/{key}로 제어하는 운영 설정
-- 4종을 시드한다. 문자열 키는 빈 문자열을 기본값으로 두어 미설정 상태를
-- 표현한다(응답에서는 null로 정규화). 기존 구조를 변경하지 않는 확장 전용.
insert into settings (key, value, description) values
    ('maintenance_mode', 'false'::jsonb,
     '점검 모드. true면 관리자 계층이 아닌 모든 인증 요청이 503(MAINTENANCE_MODE)으로 거부됩니다. 변경은 15초 이내 반영.'),
    ('maintenance_message', '""'::jsonb,
     '점검 모드 안내 문구. 비우면 기본 안내 문구를 사용합니다.'),
    ('banner_message', '""'::jsonb,
     '전역 공지 배너 문구(점검 모드와 독립 — 콘솔 상단 배너). 비우면 배너를 표시하지 않습니다.'),
    ('contact_email', '""'::jsonb,
     '운영 문의 이메일(콘솔 푸터·점검·오류 화면에 표시). 비우면 표시하지 않습니다.');
