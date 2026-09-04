-- Backoff state for the OpenRouter provisioning sweep.
--
-- The sweep had none: a refusal was recorded and the same key was retried on
-- the next tick, five minutes later, forever. The account polling path has had
-- exponential backoff since it was written, and the provisioning path is the
-- one that actually needs it -- OpenRouter documents that key creation through
-- the management API is rate-limited and publishes no number, so a run of
-- refusals is the case to survive, and hitting the same unpublished limit
-- every five minutes with an unchanged batch size delays recovery instead of
-- reaching it.
--
-- Two columns rather than one: the count decides how far to back off, and the
-- timestamp is what the worklist filters on. Deriving the wait from
-- updated_at was the cheaper-looking option and would have been wrong, since
-- every other write to the row moves that column.
alter table llm_api_keys
    add column openrouter_attempt_count int not null default 0
        constraint llm_api_keys_openrouter_attempt_count_check
            check (openrouter_attempt_count >= 0),
    add column openrouter_not_before_at timestamptz;

comment on column llm_api_keys.openrouter_attempt_count is
    '연속 실패한 OpenRouter 프로비저닝 시도 횟수. 성공하면 0으로 되돌린다.';
comment on column llm_api_keys.openrouter_not_before_at is
    '이 시각 전에는 프로비저닝을 다시 시도하지 않는다. null이면 즉시 대상.';
