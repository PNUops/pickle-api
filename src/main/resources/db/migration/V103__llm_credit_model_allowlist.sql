-- The money axis had exactly one bound: the amount. A key granted credit could
-- reach every model OpenRouter serves, so a caller could spend a term's budget
-- on the priciest model in an afternoon, and "this course uses this model" had
-- no representation at all.
--
-- This adds a per-key allow list that governs the CREDIT axis only. TOKEN-axis
-- self-serving models answer to their own daily quota and are deliberately left
-- outside it: the list is a money fence, and a self-serving model has no money
-- to fence. Curating which self-serving models a key may reach is what
-- llm_models.visibility and the gateway's existing allowedModels already do,
-- and the two axes stay apart.
--
-- Empty array means unrestricted. There is no null state anywhere: the columns
-- are not null with an empty-array default, so "no restriction" has exactly one
-- spelling from here to the gateway.

-- Patterns are validated in one place so the three columns cannot drift apart.
-- A CHECK cannot carry a subquery, so this is an immutable function instead.
--
-- Accepted: a full model name (openai/gpt-4o-mini) or a vendor prefix that
-- opens one vendor (openai/*). Refused: a bare '*', which would be a second
-- spelling of the empty array; a '*' anywhere but as the whole segment after
-- the single slash; and any uppercase, so that a stored pattern and the
-- lower-cased name the gateway compares against cannot disagree.
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
               or (e #>> '{}') !~ '^[a-z0-9][a-z0-9._:-]*(/([a-z0-9][a-z0-9._:-]*|\*))?$'
       );
$$;

comment on function llm_credit_model_patterns_valid(jsonb) is
    '상용(금액) 축 모델 허용 목록 항목의 형식 검사. 모델 이름 또는 벤더 프리픽스(vendor/*)만 받고, ''*'' 단독과 대문자는 거부한다.';

-- What the gateway serves: the live state of an issued key.
alter table llm_api_keys
    add column credit_allowed_models jsonb not null default '[]'::jsonb,
    add constraint llm_api_keys_credit_models_check
        check (llm_credit_model_patterns_valid(credit_allowed_models));

comment on column llm_api_keys.credit_allowed_models is
    '상용(금액) 축에서 이 키가 쓸 수 있는 모델 패턴. 빈 배열은 제한 없음이고 null 상태는 없다. 자체 서빙(토큰) 축은 이 목록과 무관하다.';

-- What the reviewer decided, kept beside the other granted limits.
alter table llm_key_request_details
    add column granted_credit_allowed_models jsonb not null default '[]'::jsonb,
    add constraint llm_key_request_details_credit_models_check
        check (llm_credit_model_patterns_valid(granted_credit_allowed_models)),
    -- A list without money restricts nothing, so it is a filled-in form the
    -- reviewer misread rather than a decision. Same shape as the existing rule
    -- that a reset window needs a positive amount.
    add constraint llm_key_request_details_credit_models_need_money_check
        check (jsonb_array_length(granted_credit_allowed_models) = 0
               or (granted_credit_limit is not null and granted_credit_limit > 0));

-- The prefill source for the approval form. Deliberately NOT an inheritance
-- root: the approval copies this value once, and changing it later moves no
-- already-issued key. Runtime inheritance would make an empty key list mean two
-- different things and would let one form edit rewrite every key's spending
-- permission with no audit of that change.
alter table openrouter_accounts
    add column default_credit_allowed_models jsonb not null default '[]'::jsonb,
    add constraint openrouter_accounts_credit_models_check
        check (llm_credit_model_patterns_valid(default_credit_allowed_models));

comment on column openrouter_accounts.default_credit_allowed_models is
    '승인 화면 프리필용 기본값. 런타임 상속이 아니므로 이 값을 바꿔도 이미 발급된 키는 바뀌지 않는다.';
