-- Two budget axes per key: the daily token allowance (self-serve models) and
-- a money limit (commercial models via OpenRouter), plus the model-side axis
-- marker that decides which one governs a request.
--
-- The axis is a property of the MODEL ROW, never derived from the upstream
-- kind: pnu-general stays on the TOKEN axis while an external upstream
-- temporarily serves it, and swapping that upstream moves nothing. Deriving
-- the axis from the upstream kind would shove that traffic onto the money
-- axis the day the upstream changes -- the exact defect this round corrects.
alter table llm_models
    add column budget_axis text not null default 'TOKEN'
        constraint llm_models_budget_axis_check check (budget_axis in ('TOKEN', 'CREDIT'));

comment on column llm_models.budget_axis is
    '이 모델의 사용이 어느 예산 축에 계상되는가. TOKEN=자체 서빙(일일 토큰 한도), CREDIT=상용(키별 금액 한도).';

-- The money axis on the key. credit_limit is NOT NULL with 0 meaning "the
-- commercial axis is unusable" -- deliberately unlike daily_tokens, whose
-- null means unlimited. Money has no unlimited: every usable state is a
-- number somebody granted, and the fail-closed default is 0. Existing keys
-- therefore all land at 0 (operator decision 2026-08-24: no retroactive
-- OpenRouter provisioning; a later grant provisions then).
--
-- credit_limit_reset maps to OpenRouter's limit_reset (UTC midnight windows);
-- null is the default total-cap shape (operator decision 2026-08-24: total
-- cap by default, daily/weekly/monthly configurable).
alter table llm_api_keys
    add column credit_limit numeric(12, 2) not null default 0
        constraint llm_api_keys_credit_limit_check check (credit_limit >= 0),
    add column credit_limit_reset text
        constraint llm_api_keys_credit_limit_reset_check
            check (credit_limit_reset in ('DAILY', 'WEEKLY', 'MONTHLY')),
    -- The OpenRouter-side identity and secret of this key's own OpenRouter
    -- key: hash is the management-API identifier, enc the AES-GCM ciphertext
    -- of the runtime secret (CredentialCipher). Both null until provisioned;
    -- provisioning failure leaves them null with the error recorded, and the
    -- key stays usable on the token axis (operator decision 2026-08-24).
    add column openrouter_key_hash text,
    add column openrouter_key_enc text,
    add column openrouter_provisioned_at timestamptz,
    add column openrouter_last_error text;

comment on column llm_api_keys.credit_limit is
    '상용(금액) 축 한도, USD 크레딧. 0이면 상용 축을 쓸 수 없다. daily_tokens의 null=무제한과 달리 금액에는 무제한이 없다.';
comment on column llm_api_keys.openrouter_key_enc is
    '이 키 전용 OpenRouter 키의 암호문(CredentialCipher v1). 동기화 문서에 실려 게이트웨이만 사용한다.';

-- The provisioning sweep reads only what still needs work.
create index llm_api_keys_openrouter_pending_idx on llm_api_keys (id)
    where credit_limit > 0 and openrouter_key_hash is null;

-- What the reviewer granted on the money axis, beside the token-axis columns.
alter table llm_key_request_details
    add column granted_credit_limit numeric(12, 2)
        constraint llm_key_request_details_credit_limit_check
            check (granted_credit_limit is null or granted_credit_limit >= 0),
    add column granted_credit_limit_reset text
        constraint llm_key_request_details_credit_reset_check
            check (granted_credit_limit_reset in ('DAILY', 'WEEKLY', 'MONTHLY'));

-- Drift kinds for the OpenRouter reconciliation: a key OpenRouter holds that
-- we do not know (billed invisibly), and a key whose two sides disagree about
-- being alive (revoked here but active there, or active here but gone
-- there). Values only -- no migration statement uses them, so the V80
-- same-transaction constraint does not apply here.
alter type drift_finding_kind add value if not exists 'OPENROUTER_ORPHAN';
alter type drift_finding_kind add value if not exists 'OPENROUTER_STALE';
