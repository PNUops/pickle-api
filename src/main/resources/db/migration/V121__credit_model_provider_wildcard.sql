-- Widen the common validator; all six existing column constraints keep using it.
-- No stored values change. Concrete providers may carry a leading tilde;
-- the whole-provider star includes aliases and cannot itself carry a tilde.
create or replace function llm_credit_model_patterns_valid(patterns jsonb)
returns boolean language sql immutable as $$
    select jsonb_typeof(patterns) = 'array'
       and jsonb_array_length(patterns) <= 50
       and not exists (
           select 1 from jsonb_array_elements(patterns) e
            where jsonb_typeof(e) <> 'string'
               or length(e #>> '{}') = 0
               or octet_length(e #>> '{}') > 200
               or (e #>> '{}') <> lower(e #>> '{}')
               or (e #>> '{}') !~ '^(~?[a-z0-9][a-z0-9._:-]*(/([a-z0-9][a-z0-9._:-]*\*?|\*[a-z0-9._:-]*[a-z0-9]|\*))?|\*/([a-z0-9][a-z0-9._:-]*\*?|\*[a-z0-9._:-]*[a-z0-9]|\*))$'
       );
$$;

comment on function llm_credit_model_patterns_valid(jsonb) is
    '유료 모델 허용·차단 목록의 형식 검사. 공급자는 정확한 이름 또는 *만 허용하며, 모델 자리는 정확한 이름이나 앞뒤 와일드카드 하나를 받습니다. */*-pro는 별칭을 포함한 전체 공급자에 적용됩니다. 목록별 최대 50개, 항목별 200바이트이며 단독 *와 대문자는 거부합니다.';
