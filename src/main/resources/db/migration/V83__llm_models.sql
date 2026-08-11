-- The catalogue of models the gateway may serve.
--
-- Schema only. Which models this deployment offers, and which upstream serves
-- them, is state an operator maintains — it names an upstream that exists in
-- one host's configuration and not another's — so the rows are written by the
-- ops tooling, never from here.

create table llm_models (
    id              bigint generated always as identity primary key,
    public_id       uuid   not null default gen_random_uuid(),
    -- What students see and send. Deliberately independent of the real model,
    -- so an upstream swap is a row edit rather than a change every caller sees.
    public_name     text   not null unique,
    -- Selects an upstream block in the gateway's own configuration. The api
    -- cannot check this name -- it lives on the gateway host -- so the gateway
    -- reports the names it has on every poll and drops a model that names one
    -- it does not.
    upstream_ref    text   not null,
    upstream_model  text   not null,
    fallback_ref    text,
    -- PUBLIC (default) is reachable by any key with no allow list; RESTRICTED
    -- only by a key that names it. Fail-safe: adding a model does not open it
    -- to every existing key.
    visibility      text   not null default 'PUBLIC',
    max_input_tokens  int  not null default 0,
    max_output_tokens int  not null default 0,
    -- Rows outlive their use so usage that names them stays interpretable.
    enabled         boolean not null default true,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    constraint llm_models_visibility_check check (visibility in ('PUBLIC', 'RESTRICTED'))
);

create unique index llm_models_public_id_key on llm_models (public_id);

comment on table llm_models is
    '게이트웨이가 서빙할 모델 카탈로그. 행은 운영 스크립트가 넣는다 — 어떤 업스트림이 있는지는 호스트마다 다르다.';
