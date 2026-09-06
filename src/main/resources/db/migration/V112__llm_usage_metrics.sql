-- The passthrough surface shipped without anything in the accounting to tell
-- an image call from a chat call, and images bill per image while the event
-- only carries tokens. The vendor reports the actual charge in usage.cost on
-- every priced response and it was being discarded. These columns are the api
-- half of that: they are added first so a gateway that starts emitting them
-- has somewhere to land. Until it does they read empty, which is the same
-- shape as every row already here.

alter table llm_usage_events
    add column endpoint text,
    add column served_model_name text,
    add column cost_usd numeric(14, 8)
        constraint llm_usage_events_cost_usd_check
            check (cost_usd is null or cost_usd >= 0),
    add column image_count int
        constraint llm_usage_events_image_count_check
            check (image_count is null or image_count >= 0),
    add column cached_input_tokens int not null default 0,
    add column reasoning_tokens int not null default 0,
    add column streamed boolean not null default false;

-- endpoint deliberately carries no CHECK constraint, unlike budget_axis right
-- beside it. The two look alike and are not. budget_axis is a closed two-value
-- vocabulary the control plane owns, and the api downgrades anything else to
-- null before inserting, so the constraint can never fire on live traffic.
-- A route name is the gateway's open vocabulary with no equivalent bucket to
-- downgrade into. Constrain it and the day a new route opens the insert
-- raises, the ingest transaction rolls back, the api answers 5xx, the gateway
-- reads that as transient and re-sends the same batch forever, and every later
-- event queues behind a checkpoint that has stopped moving until spool
-- retention deletes the lot. Storing a name we do not recognise costs nothing
-- by comparison.

-- The precision differs from the cumulative vendor meters (numeric(14,6)) on
-- purpose: one request has few integer digits and needs more fractional ones,
-- because the vendor reports down to about 1e-7 and rounding to cents turns a
-- light user into "spent nothing".

-- No index is added here. This is the hottest write path in the tree and the
-- ingest transaction must not get slower; the per-endpoint and per-model
-- breakdowns are served from the daily rollup, not from raw events.

alter table llm_usage_daily
    add column endpoint text,
    add column cost_usd numeric(16, 8) not null default 0,
    add column priced_requests bigint not null default 0,
    add column image_count bigint not null default 0,
    add column cached_input_tokens bigint not null default 0,
    add column reasoning_tokens bigint not null default 0,
    add column served_mismatch_requests bigint not null default 0,
    add column streamed_requests bigint not null default 0;

alter table llm_usage_daily
    add constraint llm_usage_daily_metric_counts_check check (
        cost_usd >= 0
        and priced_requests >= 0 and priced_requests <= requests
        and image_count >= 0
        and cached_input_tokens >= 0
        and reasoning_tokens >= 0
        and served_mismatch_requests >= 0 and served_mismatch_requests <= requests
        and streamed_requests >= 0 and streamed_requests <= requests);

-- cost_usd is not nullable here even though the event column is. On an event,
-- absent and zero are different claims and the difference is load-bearing. On
-- a bucket the pair (cost_usd, priced_requests) already says it: zero over
-- zero priced requests is "nothing was priced", zero over some is "it was free".
-- Making the sum nullable would only push null arithmetic into every consumer.

-- Endpoint joins the bucket key rather than becoming a set of counter columns
-- the way budget_axis did. The axis is a closed vocabulary, so three counters
-- covered it forever; a route name is open, so counters would need a migration
-- per route, and per-route sums (cost, images) would need a column per
-- (route x measure) pair rather than falling out of the grouping. Row growth
-- is not the trade it looks like: a model is reached from essentially one
-- route, so the buckets split rather than multiply.
--
-- Unlike V99 this needs no trigger for the old-writer shape. A V111 jar
-- running after this migration during an automatic rollback inserts without
-- naming endpoint, which lands null; NULLS NOT DISTINCT admits that as one
-- bucket exactly as it already does for a null key or model, and every new
-- constraint is satisfied by the zero defaults.
drop index llm_usage_daily_bucket_idx;
create unique index llm_usage_daily_bucket_idx
    on llm_usage_daily (day, key_id, public_model_name, endpoint) nulls not distinct;

comment on column llm_usage_events.endpoint is
    '요청이 들어온 경로. chat, images, images_models, embeddings이며 열린 어휘라 모르는 값도 그대로 저장한다. 필드 이전 행은 null이고 모델 이름으로 복원할 수 없다.';
comment on column llm_usage_events.served_model_name is
    '업스트림이 응답한 모델 이름. 게이트웨이가 공개 이름으로 덮어쓰기 전 값이라 벤더 폴백을 드러내는 유일한 자리다. 최상위 필드만 보므로 증거이지 보증은 아니다.';
comment on column llm_usage_events.cost_usd is
    '벤더가 이 요청 하나에 매긴 금액(USD). null은 벤더가 가격을 보고하지 않았다는 뜻이고 0이 아니다. 한도 비교와 잔액 표시는 벤더 미터가 하고 이 값은 귀속에만 쓴다.';
comment on column llm_usage_events.image_count is
    '응답이 실제로 돌려준 이미지 수. 요청한 n이 아니다. 이미지 경로 밖에서는 null이며, 채팅 경로 서버 도구가 만든 이미지는 여기 세지 않고 cost_usd에만 나타난다.';
comment on column llm_usage_events.cached_input_tokens is
    'input_tokens 중 벤더 캐시에서 나온 몫. 부분집합이라 합계에 더하면 이중 계산이다. 추정 경로와 미보고는 0이다.';
comment on column llm_usage_events.reasoning_tokens is
    'output_tokens 중 사고에 쓰인 몫. 부분집합이라 합계에 더하면 이중 계산이다. 추정 경로와 미보고는 0이다.';
comment on column llm_usage_events.streamed is
    '호출자가 스트리밍을 요청했는지. ttft_ms로는 알 수 없다(비스트리밍에도 채워진다). 필드 이전 행의 false는 관측이 아니라 기본값이다.';
comment on column llm_usage_daily.endpoint is
    '버킷 키의 넷째 축. null은 기록 이전이지 「기타」가 아니다.';
comment on column llm_usage_daily.cost_usd is
    '벤더가 보고한 금액의 합. priced_requests와 함께 읽는다 — 그 값이 0이면 이 합의 0은 「가격이 안 붙었다」이지 「무료였다」가 아니다.';
comment on column llm_usage_daily.priced_requests is
    'cost_usd가 있던 요청 수. 금액 합계를 화면에 낼 때 requests와의 차이를 반드시 함께 낸다.';
comment on column llm_usage_daily.served_mismatch_requests is
    '응답한 모델이 요청한 이름과 달랐던 요청 수. 실제 이름은 카디널리티를 우리가 통제하지 않아 버킷 키에 넣지 않고 원본에서 읽는다.';
