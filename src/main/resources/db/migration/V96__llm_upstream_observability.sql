-- Current-state observability reported by llm-gateway. This migration carries
-- schema only: upstream registry rows remain operational data managed through
-- the existing inventory path.

alter table llm_gateway_state
    add column upstream_observation_format int,
    add column last_usage_ship_success_at timestamptz,
    add column oldest_unshipped_event_at timestamptz,
    add column queued_usage_events bigint,
    add column queued_usage_bytes bigint,
    add column usage_queue_observed_at timestamptz,
    add column usage_queue_scan_failures bigint,
    add constraint llm_gateway_state_usage_queue_nonnegative check (
        (queued_usage_events is null or queued_usage_events >= 0)
        and (queued_usage_bytes is null or queued_usage_bytes >= 0)
        and (usage_queue_scan_failures is null or usage_queue_scan_failures >= 0));

create table llm_upstream_state (
    -- Deliberately no foreign key: a gateway ref that is not registered is an
    -- operational finding that must survive long enough to be shown.
    ref                          text primary key,
    configured                   boolean not null default true,
    first_seen_at                timestamptz not null default now(),
    last_reported_at             timestamptz not null default now(),
    deconfigured_at              timestamptz,

    passive_last_attempt_at      timestamptz,
    passive_last_success_at      timestamptz,
    passive_last_failure_at      timestamptz,
    passive_last_failure_type    text,
    passive_consecutive_failures int,
    passive_cooldown_until       timestamptz,

    active_last_attempt_at       timestamptz,
    active_last_success_at       timestamptz,
    active_last_failure_at       timestamptz,
    active_status                text,
    active_failure_type          text,
    active_probe_interval_seconds int,
    active_latency_ms            bigint,
    active_model_count           int,
    active_consecutive_failures  int,

    catalog_status               text,
    catalog_expected_model_count int,
    catalog_missing_model_count  int,
    catalog_unexpected_model_count int,
    catalog_missing_public_models text,

    constraint llm_upstream_state_ref_lowercase check (ref = lower(ref)),
    constraint llm_upstream_state_active_status check
        (active_status is null or active_status in
            ('OK', 'AUTH_UNVERIFIED', 'FAILED', 'UNKNOWN')),
    constraint llm_upstream_state_catalog_status check
        (catalog_status is null or catalog_status in
            ('MATCH', 'MISMATCH', 'NOT_APPLICABLE', 'UNKNOWN')),
    constraint llm_upstream_state_nonnegative check (
        (passive_consecutive_failures is null or passive_consecutive_failures >= 0)
        and (active_latency_ms is null or active_latency_ms >= 0)
        and (active_probe_interval_seconds is null or active_probe_interval_seconds > 0)
        and (active_model_count is null or active_model_count >= 0)
        and (active_consecutive_failures is null or active_consecutive_failures >= 0)
        and (catalog_expected_model_count is null or catalog_expected_model_count >= 0)
        and (catalog_missing_model_count is null or catalog_missing_model_count >= 0)
        and (catalog_unexpected_model_count is null or catalog_unexpected_model_count >= 0))
);

comment on table llm_upstream_state is
    'llm-gateway가 자기보고한 upstream별 현재 관측 상태. 시계열이 아니며, 등록부에 없는 ref도 진단을 위해 보존한다.';
comment on column llm_upstream_state.configured is
    'observation format 1의 최신 authoritative 목록에 포함됐는지. 구 gateway의 필드 누락은 이 값을 바꾸지 않는다.';
comment on column llm_upstream_state.catalog_missing_public_models is
    '게이트웨이가 보고한 누락 public model 이름의 JSON 배열. 최대 20개만 정제해 저장한다.';

-- Every consumer joins refs case-insensitively because the gateway normalises
-- them. The original V87 unique(ref) permits "Main" beside "main", which
-- would duplicate both status identity and metric buckets after lower().
create unique index llm_upstreams_ref_lower_uidx on llm_upstreams (lower(ref));
