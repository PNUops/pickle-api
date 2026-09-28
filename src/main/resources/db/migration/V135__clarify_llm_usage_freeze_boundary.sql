-- The retention sweep freezes complete KST days before deleting their raw
-- events in bounded batches. A crash can leave raw rows behind that retry
-- removes later; the old "already deleted" comments no longer describe it.
comment on table llm_usage_rollup_state is
    'LLM 일별 집계의 이벤트 ID watermark와 보존 동결 경계. 행이 없으면 watermark 0으로 첫 갱신이 백필한다. swept_before 이전의 완전한 KST 날짜는 집계를 다시 만들지 않는다. 보존 스위퍼는 이 경계를 먼저 기록하고 raw 행을 배치로 삭제하므로 중단 후 물리 삭제가 지연될 수 있다.';

comment on column llm_usage_rollup_state.swept_before is
    '이 날짜 이전의 완전한 KST 일자를 재집계하지 않는 배타적 동결 경계. raw 행을 배치 삭제하기 전에 기록하므로 중단 시 일부 raw가 남을 수 있고 다음 실행에서 삭제를 이어간다. 보존 설정을 늘리거나 꺼도 이미 동결한 경계는 뒤로 움직이지 않는다.';
