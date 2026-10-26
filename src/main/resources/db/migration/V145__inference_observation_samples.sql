-- Dedicated observations do not change request usage, audit or body retention.
create table inference_observation_samples (
    id bigint generated always as identity primary key,
    source_id uuid not null,
    producer_epoch uuid not null,
    sample_sequence bigint not null check (sample_sequence between 0 and 9007199254740991),
    observed_at timestamptz not null,
    observed_minute bigint not null,
    reporter_received_at timestamptz not null,
    received_at timestamptz not null,
    upstream_id uuid not null,
    upstream_name text not null,
    content_sha256 char(64) not null,
    host jsonb not null,
    inference jsonb not null,
    constraint inference_observation_identity_unique unique(source_id, producer_epoch, sample_sequence),
    constraint inference_observation_minute_unique unique(source_id, observed_minute),
    constraint inference_observation_minute_check check
        (observed_minute = floor(extract(epoch from observed_at) / 60)::bigint),
    constraint inference_observation_hash_check check (content_sha256 ~ '^[0-9a-f]{64}$'),
    constraint inference_observation_objects_check check
        (jsonb_typeof(host) = 'object' and jsonb_typeof(inference) = 'object')
);
create index inference_observation_source_time_idx on inference_observation_samples(source_id, observed_at, id);
create index inference_observation_expiry_idx on inference_observation_samples(observed_at, id);
create index inference_observation_received_idx on inference_observation_samples(received_at, id);
comment on table inference_observation_samples is
    '보고 원천별 1분 장비 표본. 90일 만료는 조회에 즉시 적용하고 별도 제한된 정리 작업이 물리 행을 삭제한다. 사용자 요청 로그·장비 등록부가 아니다.';
comment on column inference_observation_samples.upstream_id is
    '수신 당시 서버 설정에 고정된 공급원의 공개 ID. 현재 등록부 삭제나 이름 변경으로 과거 표본을 재귀속하지 않는다.';
comment on column inference_observation_samples.content_sha256 is
    '정확히 수신한 UTF-8 HTTP 본문 SHA256. 동일 논리 identity의 다른 바이트는 충돌이며 본문 원문은 중복 저장하지 않는다.';
