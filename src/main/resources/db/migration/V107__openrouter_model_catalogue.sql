-- A cached copy of the vendor's public model catalogue, so an approver picks a
-- model from a list with its price beside it instead of typing an id.
--
-- Schema only: every row here is written by the refresh job from the vendor's
-- own listing. Nothing about this environment is seeded.

-- One row, like llm_gateway_state. The catalogue is the same for every
-- institution, so its freshness is a single fact rather than a per-account one.
-- Reusing the per-account poll columns would have multiplied one vendor call by
-- the number of accounts for a list that does not vary between them.
create table openrouter_catalogue_state (
    id                  boolean primary key default true,
    last_attempt_at     timestamptz,
    -- The clock everything else is judged against. A refresh that fetched
    -- nothing usable does not move it, so a stale list cannot masquerade as a
    -- fresh empty one.
    last_success_at     timestamptz,
    -- Kept for display, not for control flow: the reason a list is old is what
    -- an approver needs in order to tell "the vendor is down" from "nobody has
    -- looked in a while".
    last_error          text,
    consecutive_failures int not null default 0,
    -- How many rows the last successful fetch carried, before disabling.
    -- Compared against the table's own count to show a shrinking catalogue.
    last_model_count    int,
    constraint openrouter_catalogue_state_singleton check (id),
    constraint openrouter_catalogue_state_failures_nonnegative
        check (consecutive_failures >= 0),
    constraint openrouter_catalogue_state_count_nonnegative
        check (last_model_count is null or last_model_count >= 0)
);

-- The single row is not inserted here. llm_gateway_state sets the precedent:
-- its row is created by an upsert in application code, so the table's existence
-- and its contents stay separate concerns and a migration carries no data.

-- Rows outlive their listing, following llm_models and llm_upstream_state: a
-- model that disappears upstream is switched off rather than deleted, because a
-- key already fenced to it still names it and an approver reading that fence
-- needs the name to resolve to something.
create table openrouter_catalogue_model (
    model_id            text primary key,
    display_name        text not null,
    description         text,
    context_length      int,
    -- Vendor prices are per token and span four orders of magnitude, so the
    -- scale is generous and the column stays exact. Null is unknown, and zero
    -- is a real value the vendor uses for its free tier.
    prompt_price        numeric(20, 12),
    completion_price    numeric(20, 12),
    listed              boolean not null default true,
    first_seen_at       timestamptz not null default now(),
    last_listed_at      timestamptz not null default now(),
    delisted_at         timestamptz,
    constraint openrouter_catalogue_model_id_lowercase check (model_id = lower(model_id)),
    constraint openrouter_catalogue_model_id_nonblank check (length(btrim(model_id)) > 0),
    constraint openrouter_catalogue_model_context_positive
        check (context_length is null or context_length > 0),
    constraint openrouter_catalogue_model_prices_nonnegative check (
        (prompt_price is null or prompt_price >= 0)
        and (completion_price is null or completion_price >= 0)),
    -- listed and delisted_at say the same thing and must not disagree.
    constraint openrouter_catalogue_model_delisted_consistent
        check (listed = (delisted_at is null))
);

-- The picker reads the listed rows in price order; the fence resolves a single
-- id it already holds. Both are covered without an index on every column.
create index openrouter_catalogue_model_listed_idx
    on openrouter_catalogue_model (listed, model_id);

comment on table openrouter_catalogue_state is
    'OpenRouter 모델 카탈로그 캐시의 갱신 상태. 한 행만 존재하며 신선도 판정의 기준이다.';
comment on table openrouter_catalogue_model is
    'OpenRouter가 공개하는 모델 목록의 캐시. 벤더에서 사라진 모델은 지우지 않고 listed를 끈다.';
comment on column openrouter_catalogue_model.listed is
    '벤더의 마지막 성공한 목록에 있었는지. 거짓이면 delisted_at이 채워진다.';
