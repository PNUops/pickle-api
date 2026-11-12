-- One latest automatic report per dedicated source; no deployment data is seeded.
create table platform_hardware_observations (
    source_id uuid primary key,
    schema_version integer not null check (schema_version = 1),
    observed_at timestamptz not null,
    received_at timestamptz not null,
    assets jsonb not null check (jsonb_typeof(assets) = 'array')
);

comment on table platform_hardware_observations is
    '전용 수집 원천별 최신 자동 장비 보고 한 건. 이전 시각과 같은 시각의 보고는 덮어쓰지 않아요.';
comment on column platform_hardware_observations.observed_at is
    '보고에 포함된 장비 중 가장 이른 실제 측정 시각이에요.';
comment on column platform_hardware_observations.assets is
    '검증한 형식으로 다시 직렬화한 장비 사양이에요. 주소와 시리얼, 임의 추가 필드는 저장하지 않아요.';
