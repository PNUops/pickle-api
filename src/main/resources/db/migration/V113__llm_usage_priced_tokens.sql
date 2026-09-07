-- Split the token counts by whether the provider reported an amount.
--
-- The administrative model table shows one row per model with a single token
-- count and a single amount. When only part of a model's traffic came back with
-- a cost, that row can say how many requests were unpriced but not what they
-- cost, and the reader has no way to estimate: the tokens of the priced part
-- and the unpriced part are added together, so the known rate cannot be applied
-- to the unknown volume. Two sums make the row two rows.
--
-- These are subsets of input_tokens and output_tokens, never additions, which
-- is the same rule cached_input_tokens and reasoning_tokens follow. The check
-- constraints say so rather than leaving it to a comment.
--
-- Existing rows land on the default 0, which is wrong for every day already
-- rolled up: those days had priced requests whose tokens nobody counted. The
-- fix is not an UPDATE here -- the daily table is rebuilt per day from the raw
-- events, so the only correct source is the rebuild itself. Reset the watermark
-- after deploying and the next tick recomputes every unswept day:
--
--   update llm_usage_rollup_state set last_event_id = 0;
--
-- That statement is left to the operator rather than run here. Flyway starts
-- inside the new jar, so the code that fills these columns is already loaded --
-- the reason is not that it could not work. It is that the reset makes the next
-- tick recompute the whole retained window at once, and when that runs is a
-- load decision about a live host rather than part of landing a schema.
--
-- **The reset only heals days the retention sweep has not passed.** The rebuild
-- skips anything before `swept_before`, so once a sweep has run, a zero left in
-- an earlier day stays there and no reset brings it back. Today that column is
-- null and nothing has been swept, which is the only reason the recovery below
-- is complete; it stops being complete the first time the sweeper runs.
--
-- The same hazard runs the other way and is quieter. An older jar's rebuild
-- names none of these four columns, so the insert succeeds and the defaults
-- win: a day that had them filled comes back zeroed, and every constraint here
-- is satisfied by a zero, so nothing complains. The deploy script rolls back to
-- the previous jar on its own when a health check fails, which means this can
-- happen without anyone deciding it. Recovery is the same reset, under the same
-- sweep condition, and only if someone knows to look -- so the rollback path in
-- the deploy runbook says to.
alter table llm_usage_daily
  add column priced_input_tokens bigint not null default 0
      check (priced_input_tokens >= 0 and priced_input_tokens <= input_tokens),
  add column priced_output_tokens bigint not null default 0
      check (priced_output_tokens >= 0 and priced_output_tokens <= output_tokens),
  add column priced_latency_ms_sum bigint not null default 0
      check (priced_latency_ms_sum >= 0 and priced_latency_ms_sum <= latency_ms_sum),
  add column priced_failed bigint not null default 0
      check (priced_failed >= 0 and priced_failed <= failed);

comment on column llm_usage_daily.priced_input_tokens is
  '공급자가 금액을 알려 준 요청이 쓴 입력 토큰 수. input_tokens의 부분집합이며 더하는 값이 아닙니다. 이 컬럼이 생기기 전에 집계된 날은 0이고, 그 0은 「없었다」가 아니라 「세지 않았다」입니다.';
comment on column llm_usage_daily.priced_output_tokens is
  '공급자가 금액을 알려 준 요청이 쓴 출력 토큰 수. output_tokens의 부분집합입니다.';
comment on column llm_usage_daily.priced_latency_ms_sum is
  '공급자가 금액을 알려 준 요청의 응답 시간 합(ms). latency_ms_sum의 부분집합이며, 두 행으로 갈린 모델 표가 각 행의 평균 응답을 자기 요청 수로 나누어 내기 위한 값입니다.';
comment on column llm_usage_daily.priced_failed is
  '공급자가 금액을 알려 준 요청 가운데 실패한 수. 게이트웨이가 응답을 정산할 때만 금액을 쓰므로 실제로는 언제나 0이지만, 그 불변식은 이 스키마가 보증하는 것이 아니라 다른 레포의 동작이라 세어서 갖습니다. 두 행으로 갈린 모델 표가 각 행의 실패율을 가정이 아니라 자료에서 내기 위한 값입니다.';
