-- Widen the money-fence pattern to admit the vendor's floating aliases.
--
-- OpenRouter lists names like ~anthropic/claude-sonnet-latest, which always
-- resolve to the newest model of a family. Thirteen of them were in the
-- catalogue when this was written and the V102 pattern refused all thirteen,
-- because it required the entry to start with [a-z0-9]. They route through
-- passthrough regardless, so the effect was that a fenced key could not reach
-- a model an unfenced key could, and "pin this course to the latest Sonnet"
-- could not be expressed at all.
--
-- Only the leading position changes. Everything V102 refused for a reason it
-- still refuses: a bare '*', a '*' outside the segment after the single slash,
-- uppercase, an empty or oversized entry, a non-string element, more than 50
-- entries. '~anthropic/*' and 'anthropic/*' stay separate prefixes, which is a
-- property of the gateway's matcher rather than of this function.
--
-- Replacing the function body is enough: the CHECK constraints call it by
-- name, and this change only widens what it accepts, so every row already
-- stored is still valid and nothing needs revalidating. The widening direction
-- is what makes that true — a narrowing change here would leave rows that the
-- constraint would now refuse, and no error would announce them, because a
-- CHECK is evaluated on write.
create or replace function llm_credit_model_patterns_valid(patterns jsonb)
returns boolean language sql immutable as $$
    select jsonb_typeof(patterns) = 'array'
       and jsonb_array_length(patterns) <= 50
       and not exists (
           select 1 from jsonb_array_elements(patterns) e
            where jsonb_typeof(e) <> 'string'
               or length(e #>> '{}') = 0
               or length(e #>> '{}') > 200
               or (e #>> '{}') <> lower(e #>> '{}')
               or (e #>> '{}') !~ '^~?[a-z0-9][a-z0-9._:-]*(/([a-z0-9][a-z0-9._:-]*|\*))?$'
       );
$$;

comment on function llm_credit_model_patterns_valid(jsonb) is
    '상용(금액) 축 모델 허용 목록 항목의 형식 검사. 모델 이름 또는 벤더 프리픽스(vendor/*)만 받고, 벤더의 부동 별칭을 위해 선행 ~를 허용하며, ''*'' 단독과 대문자는 거부한다.';
