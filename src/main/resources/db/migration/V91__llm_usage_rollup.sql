-- Aggregation floor for LLM usage statistics: a daily rollup of the raw
-- events, plus the two columns and the small table that carry what OpenRouter
-- knows about a key's spend.
--
-- Until now nothing aggregated llm_usage_events: every read of a key's series
-- ran generate_series over the raw rows, which answers one key's daily line
-- and nothing else. A model axis and a platform-wide administrator view need
-- an aggregate that already exists when the query starts.
--
-- The rollup is REBUILT, never incremented. A usage batch that arrives late
-- carries calls belonging to a day the rollup already wrote, so the refresh
-- deletes and re-inserts whole day buckets from the raw events rather than
-- adding to a running total. Closing a day on arrival time would be the same
-- defect that made requested_at the ordering column in the first place.
create table llm_usage_daily (
    day                date   not null,
    key_id             bigint references llm_api_keys (id),
    public_model_name  text,
    requests           bigint not null default 0,
    succeeded          bigint not null default 0,
    rate_limited       bigint not null default 0,
    failed             bigint not null default 0,
    input_tokens       bigint not null default 0,
    output_tokens      bigint not null default 0,
    estimated_requests bigint not null default 0,
    latency_ms_sum     bigint not null default 0
);

-- Both dimensions are legitimately null and each null is one real bucket, not
-- an unknown: a null key_id is the traffic that never resolved to a key, and a
-- null model is a request that failed before a model was chosen. NULLS NOT
-- DISTINCT is what makes the rebuild's re-insert collide with its own previous
-- row instead of silently accumulating duplicates beside it.
create unique index llm_usage_daily_bucket_idx
    on llm_usage_daily (day, key_id, public_model_name) nulls not distinct;
create index llm_usage_daily_key_idx on llm_usage_daily (key_id, day);

comment on table llm_usage_daily is
    'llm_usage_events의 키 x KST 일자 x 모델 일별 집계. 원본에서 일자 단위로 지우고 다시 넣어 재계산하며, 절대 증분 누적하지 않는다(늦게 도착한 이벤트가 정상이기 때문). 사용량이 없는 날은 행이 없으므로 추이 조회는 반드시 generate_series에 재조인해야 한다.';
comment on column llm_usage_daily.key_id is
    'null은 키로 귀속되지 않은 요청. 기관 스코프 조회에서는 제외된다(어느 기관의 것도 아니다).';
comment on column llm_usage_daily.latency_ms_sum is
    '평균 지연은 읽을 때 sum/requests로 낸다. 백분위는 이 표로 낼 수 없어 원본 이벤트를 읽는다.';

-- Where the refresh left off. One row by construction, like
-- llm_gateway_state. Deliberately NOT seeded: no row reads as watermark 0, and
-- a refresh starting at 0 is the backfill -- so a fresh database and a
-- database with years of events take the same code path, and neither needs a
-- migration to insert anything.
create table llm_usage_rollup_state (
    id            boolean primary key default true,
    last_event_id bigint      not null default 0,
    swept_before  date,
    updated_at    timestamptz not null default now(),
    constraint llm_usage_rollup_state_single_row check (id)
);

comment on table llm_usage_rollup_state is
    '롤업 갱신이 어디까지 반영했는지. 행이 없으면 워터마크 0이고 첫 갱신이 곧 백필이다. 보존 스위퍼는 이 값 이하의 이벤트만 지운다.';
comment on column llm_usage_rollup_state.swept_before is
    '보존 스위퍼가 실제로 지운 경계. 이 날짜 이전 일자는 원본이 없으므로 롤업 행이 유일한 기록이고 갱신 잡이 재계산하지 않는다. 설정에서 유도하지 않고 실제로 지운 시점을 적는 이유는, 보존을 껐다 켜도 이미 지워진 사실이 사라지지 않기 때문이다. 앞으로만 움직인다.';

-- What OpenRouter says this key has spent. The money axis is enforced there,
-- so their cumulative figure is the truthful one and ours would always lag a
-- batch; the reconciler that already lists their keys every half hour writes
-- these, which is why they carry the time they were read.
alter table llm_api_keys
    add column openrouter_usage numeric(12, 2)
        constraint llm_api_keys_openrouter_usage_check
            check (openrouter_usage is null or openrouter_usage >= 0),
    add column openrouter_usage_at timestamptz;

comment on column llm_api_keys.openrouter_usage is
    'OpenRouter가 보고한 이 키의 누적 사용액(USD). 30분 주기 대사가 갱신하므로 항상 그만큼 낡았고, 그래도 우리 이벤트로 계산한 금액보다는 정확하다.';

-- Spend history, so a depletion forecast has a slope to read. One row per
-- reconciliation per key; nothing else writes here and no screen reads a
-- single row -- only the delta between two.
create table llm_credit_usage_snapshots (
    id           bigint generated always as identity primary key,
    key_id       bigint         not null references llm_api_keys (id),
    usage_amount numeric(12, 2) not null,
    credit_limit numeric(12, 2),
    captured_at  timestamptz    not null default now()
);

create index llm_credit_usage_snapshots_key_idx
    on llm_credit_usage_snapshots (key_id, captured_at desc);

comment on table llm_credit_usage_snapshots is
    '금액 축 소진 예상을 위한 누적 사용액 이력. 대사가 매 회 한 행씩 쌓고 오래된 것은 같은 잡이 지운다. 두 시점의 차이만 쓰이므로 한 행 자체에는 의미가 없다.';
