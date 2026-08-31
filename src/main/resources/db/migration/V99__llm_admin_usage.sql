-- Administrator LLM usage aggregates need the request-time budget axis,
-- token-weighted estimation quality, an unambiguous rollup success stamp and
-- two bounded raw-event access paths. Existing usage cannot be assigned to a
-- historical budget axis honestly, so every existing daily request starts in
-- UNKNOWN. Only daily buckets whose raw events still exist receive an exact
-- estimated-token backfill.

alter table llm_usage_events
    add column budget_axis text
        constraint llm_usage_events_budget_axis_check
            check (budget_axis in ('TOKEN', 'CREDIT'));

alter table llm_usage_daily
    add column token_axis_requests bigint not null default 0,
    add column credit_axis_requests bigint not null default 0,
    add column unknown_axis_requests bigint not null default 0,
    add column estimated_tokens bigint;

update llm_usage_daily
   set unknown_axis_requests = requests;

-- A V98 jar may run after V99 during automatic rollback. Its rollup INSERT
-- does not name the new axis columns, so all three receive zero defaults. Turn
-- exactly that old-writer shape into UNKNOWN before constraints run; a new
-- writer always sends a complete split and is left untouched.
create function fill_llm_usage_daily_unknown_axis()
returns trigger language plpgsql as $$
begin
    if new.requests > 0
       and new.token_axis_requests = 0
       and new.credit_axis_requests = 0
       and new.unknown_axis_requests = 0 then
        new.unknown_axis_requests := new.requests;
    end if;
    return new;
end $$;

create trigger trg_llm_usage_daily_unknown_axis
    before insert or update of requests, token_axis_requests,
        credit_axis_requests, unknown_axis_requests
    on llm_usage_daily
    for each row execute function fill_llm_usage_daily_unknown_axis();

alter table llm_usage_daily
    add constraint llm_usage_daily_axis_counts_check check (
        token_axis_requests >= 0
        and credit_axis_requests >= 0
        and unknown_axis_requests >= 0
        and token_axis_requests + credit_axis_requests
            + unknown_axis_requests = requests),
    add constraint llm_usage_daily_estimated_tokens_check
        check (estimated_tokens is null or estimated_tokens >= 0);

update llm_usage_daily d
   set estimated_tokens = source.estimated_tokens
  from (
      select (e.requested_at at time zone 'Asia/Seoul')::date as day,
             e.key_id,
             e.public_model_name,
             coalesce(sum(e.input_tokens::bigint + e.output_tokens::bigint)
                 filter (where e.estimated), 0) as estimated_tokens
        from llm_usage_events e
       group by (e.requested_at at time zone 'Asia/Seoul')::date,
                e.key_id, e.public_model_name
  ) source
 where d.day = source.day
   and d.key_id is not distinct from source.key_id
   and d.public_model_name is not distinct from source.public_model_name
   and not exists (
       select 1
         from llm_usage_rollup_state s
        where s.swept_before is not null
          and d.day < s.swept_before
   );

alter table llm_usage_rollup_state
    add column last_success_at timestamptz;

create index llm_usage_events_received_at_idx
    on llm_usage_events (received_at desc);

create index llm_usage_events_limit_pressure_idx
    on llm_usage_events (requested_at desc, key_id, error_type)
    where error_type in ('quota_exhausted', 'credit_exhausted',
        'rate_limit_requests', 'rate_limit_tokens', 'rate_limit_concurrency');

comment on column llm_usage_events.budget_axis is
    '요청 당시 gateway가 적용한 예산 축. 구 gateway 또는 유효하지 않은 보고는 null이며 UNKNOWN으로 집계한다.';
comment on column llm_usage_daily.estimated_tokens is
    'estimated=true event의 입력+출력 token 합. 원본이 이미 보존 경계 밖이면 null이다.';
comment on column llm_usage_rollup_state.last_success_at is
    'Rollup이 advisory lock을 얻고 정상 종료한 마지막 시각. 새 event가 없는 성공 실행도 갱신한다.';
