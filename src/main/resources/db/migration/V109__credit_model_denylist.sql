-- The money fence had one direction. A key could name the models it may spend
-- on and everything else was shut, which states "this course uses this model"
-- exactly and states "anything but the expensive one" not at all: expressing
-- that needs one entry per vendor the catalogue happens to carry today, and it
-- reopens the moment a vendor publishes a model nobody has enumerated yet.
--
-- This adds the opposite list beside the existing one and widens the pattern
-- both share so a model segment may carry a leading or a trailing star. Deny
-- wins where the two disagree. Each list on its own keeps the rule it already
-- had — an empty list places no constraint — so no third state appears: an
-- empty allow list opens every model and an empty deny list closes none. The
-- TOKEN axis (self-serving models) is outside both, as it was outside the
-- first one: these lists fence money, and that axis has none to fence.

-- Body replacement only, and that is the whole change to the rule. The three
-- allow constraints installed by V102 call this function by name and so will
-- the three deny constraints below, so there is one place for the syntax.
--
-- No stored row needs revalidating, and the reason is the direction: this
-- widens, so everything V102 and V104 accepted is still accepted. A narrowing
-- change could not be made this way. A CHECK is evaluated on write, so rows the
-- new body refuses would simply sit in the table, valid by nobody's rule and
-- announced by nothing, until some later write happened to touch them.
--
-- What the segment after the single slash may now be: an exact name, a name
-- with a trailing star, a leading star whose tail is non-empty and ends
-- alphanumeric, or a bare star meaning the whole vendor. One star per entry.
-- The vendor half still takes no star: vendor names are prefixes of each other
-- (meta and meta-llama, bytedance and bytedance-seed), so 'openai*' would reach
-- a vendor nobody named. Everything else V102 refused it still refuses — a bare
-- '*', uppercase, an empty or oversized entry, a non-string element, more than
-- 50 entries.
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
               or (e #>> '{}') !~ '^~?[a-z0-9][a-z0-9._:-]*(/([a-z0-9][a-z0-9._:-]*\*?|\*[a-z0-9._:-]*[a-z0-9]|\*))?$'
       );
$$;

comment on function llm_credit_model_patterns_valid(jsonb) is
    '상용(금액) 축 모델 허용 목록과 차단 목록 항목의 형식 검사. 두 목록의 문법은 같고 뜻만 반대다. 모델 이름, 벤더 프리픽스(vendor/*), 모델 자리의 앞뒤 와일드카드 하나를 받고, 벤더 자리의 와일드카드와 ''*'' 단독과 대문자는 거부한다.';

-- What the gateway serves: the live state of an issued key.
--
-- Deliberately without the "needs a positive amount" rule that guards the allow
-- list. On the allow side a list with no money behind it restricts nothing and
-- so is a misread form; on the deny side "this key may not call that model"
-- stays true at an amount of zero, and stays true if somebody funds the key
-- tomorrow. Requiring money here would quietly drop the reviewer's refusal at
-- the moment it costs nothing to keep.
alter table llm_api_keys
    add column credit_denied_models jsonb not null default '[]'::jsonb,
    add constraint llm_api_keys_credit_denied_models_check
        check (llm_credit_model_patterns_valid(credit_denied_models));

comment on column llm_api_keys.credit_denied_models is
    '상용(금액) 축에서 이 키가 쓸 수 없는 모델 패턴. 빈 배열은 차단 없음이고 null 상태는 없다. 허용 목록과 함께 걸리면 차단이 이긴다. 자체 서빙(토큰) 축은 이 목록과 무관하다.';

-- What the reviewer decided, kept beside the other granted limits.
alter table llm_key_request_details
    add column granted_credit_denied_models jsonb not null default '[]'::jsonb,
    add constraint llm_key_request_details_credit_denied_models_check
        check (llm_credit_model_patterns_valid(granted_credit_denied_models));

comment on column llm_key_request_details.granted_credit_denied_models is
    '승인자가 부여한 상용(금액) 축 차단 목록. 빈 배열은 차단 없음이다. 허용 목록과 달리 금액 한도가 0이어도 남는다.';

-- The prefill source for the approval form, on the same terms as the allow-list
-- default: copied once at approval, never inherited at runtime, so editing it
-- moves no already-issued key.
alter table openrouter_accounts
    add column default_credit_denied_models jsonb not null default '[]'::jsonb,
    add constraint openrouter_accounts_credit_denied_models_check
        check (llm_credit_model_patterns_valid(default_credit_denied_models));

comment on column openrouter_accounts.default_credit_denied_models is
    '승인 화면 프리필용 차단 목록 기본값. 런타임 상속이 아니므로 이 값을 바꿔도 이미 발급된 키는 바뀌지 않는다.';
