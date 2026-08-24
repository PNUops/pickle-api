-- The registry of LLM capacity sources: on-prem serving hardware and external
-- paid APIs, one table for both (the org-supply design's decision -- what the
-- kinds share is exactly ownership, policy and display, so a kind column
-- carries the difference).
--
-- This table is a record of ownership and policy, not of connectivity: the
-- base URL and any credential stay in the gateway host's environment, which
-- remains the sole owner of upstream addresses. llm_models.upstream_ref keeps
-- its string coupling (no foreign key), preserving the gateway's fail-safe of
-- dropping a model whose ref its host does not have.
--
-- SEED ROWS BELOW, by operator approval given 2026-08-24 before this file was
-- written (the migration convention's hard gate). The approval covers exactly
-- three rows -- openai, openrouter, dgx -- and nothing else. They are the
-- registry's own subjects rather than one host's inventory: every deployment
-- of this platform routes by these refs, and omitting the currently serving
-- upstream (openai) would leave the live route unregistered.
create table llm_upstreams (
    id           bigint generated always as identity primary key,
    public_id    uuid    not null default gen_random_uuid(),
    -- Matches the gateway's env block name (LLMGW_UPSTREAM_<REF>_*) and
    -- llm_models.upstream_ref. Lowercase by convention; the gateway matches
    -- case-insensitively.
    ref          text    not null unique,
    kind         text    not null,
    display_name text    not null,
    -- Ownership: which organisation supplied this capacity. Null with
    -- dedicated=false is today's meaning (shared, unattributed); assigning an
    -- owner is an operational row write, never a migration.
    org_id       bigint  references orgs (id),
    dedicated    boolean not null default false,
    enabled      boolean not null default true,
    -- When true, public model names the catalog does not list are forwarded
    -- to this upstream as-is (CREDIT budget axis). At most one row may hold
    -- it; the partial unique index below is that guarantee.
    passthrough  boolean not null default false,
    note         text,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    constraint llm_upstreams_kind_check check (kind in ('ON_PREM', 'EXTERNAL_API')),
    constraint llm_upstreams_dedicated_check check (not dedicated or org_id is not null)
);

create unique index llm_upstreams_public_id_key on llm_upstreams (public_id);
create unique index llm_upstreams_single_passthrough on llm_upstreams (passthrough)
    where passthrough;

comment on table llm_upstreams is
    'LLM 용량 공급원 등록부(온프렘 서빙·외부 유료 API). 소유·정책·표시의 기록이며 접속 정보는 게이트웨이 호스트 env가 정본이다.';
comment on column llm_upstreams.passthrough is
    '카탈로그에 없는 모델명을 이 업스트림으로 그대로 전달할지. 최대 한 행만 가질 수 있다.';

-- The three registry rows the 2026-08-24 approval covers. dgx is the seat
-- prepared for the on-prem serving box (dgx1): disabled until serving stands,
-- so nothing routes to it and the row is purely a registered name.
insert into llm_upstreams (ref, kind, display_name, enabled, passthrough, note) values
    ('openai',     'EXTERNAL_API', 'OpenAI',     true,  false,
     '자체 서빙이 서기 전까지 pnu- 모델을 임시로 받치는 외부 업스트림'),
    ('openrouter', 'EXTERNAL_API', 'OpenRouter', true,  true,
     '상용 모델 경로. 키별 자격증명으로 호출되며 금액 한도는 OpenRouter가 강제한다'),
    ('dgx',        'ON_PREM',      'DGX Spark',  false, false,
     '자체 서빙 하드웨어 자리. vLLM 서빙이 서면 활성화한다');
