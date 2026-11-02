-- Current key/axis facts and stable threshold dedup.
create table notification_preferences (
    user_id bigint primary key references users(id) on delete cascade,
    usage_mail_enabled boolean not null default true,
    updated_at timestamptz not null default now()
);
comment on table notification_preferences is '개인 사용량 임계 알림 메일 설정. 행이 없으면 수신이며 콘솔·다른 알림 정책은 바꾸지 않음';

create table llm_key_usage_alert_state (
    key_id bigint not null references llm_api_keys(id) on delete cascade,
    axis text not null check (axis in ('TOKEN','CREDIT')),
    basis_key text,
    highest_stage smallint not null default 0 check (highest_stage in (0,80,100)),
    credit_snapshot jsonb check (credit_snapshot is null or jsonb_typeof(credit_snapshot)='object'),
    last_recipient_count integer not null default 0 check (last_recipient_count>=0),
    updated_at timestamptz not null default now(),
    primary key(key_id,axis)
);
comment on table llm_key_usage_alert_state is '키·축별 최신 관측 기준과 최고 경고 단계 한 행. 알림 본문·주소·이력·리소스 제어 상태를 복제하지 않음';
comment on column llm_key_usage_alert_state.credit_snapshot is '동일 공급자 응답의 한도·잔여·설정·조회 시각과 로컬 결합 기준. 정밀도 보존용 십진 문자열이며 원요청·자격증명·누적/기간별 사용 이력은 저장하지 않음';
comment on column llm_key_usage_alert_state.basis_key is '키·축·실제 기간·정규화 한도·리셋의 안정된 식별자. 관측 시각·generation·정책 revision을 사용하지 않음';

-- A claimed usage mail can be withheld before SMTP after authority or preference changes.
alter table mail_delivery_attempts drop constraint mail_delivery_attempts_outcome_check;
alter table mail_delivery_attempts add constraint mail_delivery_attempts_outcome_check
    check (outcome in ('STARTED','SENT','FAILED','SKIPPED','UNKNOWN'));
comment on column mail_delivery_attempts.outcome is '발송 시도 결과. 새 사용량 알림은 SMTP 직전 현재 인가·수신 설정에 따라 SKIPPED로 끝날 수 있음';
