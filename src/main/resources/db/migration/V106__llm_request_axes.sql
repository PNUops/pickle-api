-- The money axis existed only on the granted side. A student could ask for a key
-- but not for an amount, so the approver invented the number: there was nothing
-- on the request saying what the applicant thought they needed, or even whether
-- they wanted commercial models at all.
--
-- Three columns fix that, and the two booleans are the point. Which axes a key
-- is for cannot be inferred from the limits being null, because a null limit is
-- the ordinary request -- it means "the service default", not "I do not want
-- this axis". Asking outright is the only way to tell "campus models, defaults
-- are fine" from "commercial models, and here is my budget".
--
-- Defaults describe the request everyone was already making: campus models at
-- the service defaults, no commercial spend. Rows written before this migration
-- get exactly that, which is what they meant.
--
-- No constraint ties the requested amount to an OpenRouter account. That
-- constraint lives on llm_api_keys, where the money is actually spent, and the
-- account is chosen at approval -- a request is a number somebody is asking
-- for, not a claim on a funding source.

alter table llm_key_request_details
    add column req_use_campus boolean not null default true,
    add column req_use_commercial boolean not null default false,
    add column req_credit_limit numeric(12, 2),
    add constraint llm_key_request_details_req_credit_limit_check
        check (req_credit_limit is null or req_credit_limit > 0),
    -- 축을 하나도 고르지 않은 신청은 무엇을 달라는 것인지 말하지 않은 것이다.
    add constraint llm_key_request_details_req_axis_check
        check (req_use_campus or req_use_commercial),
    -- 유료를 쓰지 않겠다면서 금액을 적는 것은 앞뒤가 맞지 않는다.
    add constraint llm_key_request_details_req_credit_axis_check
        check (req_credit_limit is null or req_use_commercial);

comment on column llm_key_request_details.req_use_campus is
    '신청자가 교내 자체 서빙 모델을 쓰겠다고 했는지.';
comment on column llm_key_request_details.req_use_commercial is
    '신청자가 유료(상용) 모델을 쓰겠다고 했는지. 금액은 req_credit_limit이 든다.';
comment on column llm_key_request_details.req_credit_limit is
    '신청자가 요청한 금액 한도(USD). 승인 화면이 부여 금액에 프리필한다.';
