-- 웹 터미널 전역 킬 스위치 (계약 v0.10.0, 내부 웹 터미널 계약). 브라우저
-- xterm.js 웹 터미널 기능을 켜고 끄는
-- 운영자 제어 설정 1종을 시드한다. 기본값 false — 기능은 인프라 배선이
-- 끝난 뒤 운영자가 명시적으로 켠다(SSH 게이트웨이 ssh_gateway_enabled와 동일
-- 패턴). 세션·티켓은 인메모리라 스키마 추가는 이 킬 스위치뿐이다. 확장 전용.
insert into settings (key, value, description) values
    ('web_terminal_enabled', 'false'::jsonb,
     '웹 터미널(브라우저 xterm.js) 전역 킬 스위치. false면 티켓 발급·재교환이 모두 거부되고, '
     || '진행 중이던 세션은 다음 60초 재검증에서 종료됩니다. 우선순위: 킬 스위치 > per-VM 차단 > 멤버십.');
