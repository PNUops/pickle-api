-- LLM API keys: the request detail, the keys themselves, the usage they
-- produce, and the counter the gateway polls.
--
-- Schema only. Which models the service offers is state an operator maintains,
-- so the catalogue rows are seeded by the ops tooling and never from here.

-- PENDING is the state between approval and issue. The approver decides that
-- somebody may have a key; only its owner may ever see the secret, and the
-- approver is not in the room when they do. So approval creates the key and its
-- access list, and the owner mints the plaintext themselves -- once.
create type llm_api_key_status as enum ('PENDING', 'ACTIVE', 'SUSPENDED', 'REVOKED', 'EXPIRED');

-- What this kind of request asks for, and what the reviewer granted of it. The
-- granted period stays on request_reviews: every resource type has one.
create table llm_key_request_details (
    request_id           bigint primary key references requests (id),
    req_purpose          text,
    req_rpm              int,
    req_tpm              int,
    req_daily_tokens     bigint,
    granted_rpm          int,
    granted_tpm          int,
    granted_concurrency  int,
    granted_daily_tokens bigint
);

comment on table llm_key_request_details is
    'LLM API 키 신청의 종류별 항목. 공통 항목은 requests에, 부여 기간은 request_reviews에 있다.';

create table llm_api_keys (
    id            bigint generated always as identity primary key,
    public_id     uuid   not null default gen_random_uuid(),
    workspace_id  bigint not null references workspaces (id),
    org_id        bigint not null references orgs (id),
    request_id    bigint not null references requests (id),
    name          text   not null,
    purpose       text,
    -- The plaintext is shown once at issue and stored nowhere: this is the hex
    -- sha256 of the whole bearer token, which is also what the gateway computes
    -- from what a student presents. Losing it means reissuing, by design.
    --
    -- Null until the owner issues. A key with no hash authenticates nothing, so
    -- it is simply absent from the document the gateway polls -- there is no
    -- state to publish about a secret that does not exist yet.
    token_hash    char(64),
    -- The first characters of the plaintext, so a list can tell two keys apart
    -- without holding anything that authenticates.
    token_prefix  text,
    status        llm_api_key_status not null default 'PENDING',
    expires_at    timestamptz,
    -- Reported by the gateway with the usage it ships, so it lags by a batch.
    last_used_at  timestamptz,
    -- Enforced at the gateway, carried in the document it polls. Null means the
    -- key sets no limit of its own and the gateway's default applies.
    rpm           int,
    tpm           int,
    concurrency   int,
    -- Prompt and response capture. Off unless the owner turns it on, and the
    -- storage behind it does not exist yet.
    record_bodies boolean not null default false,
    created_by    bigint not null references users (id),
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    revoked_at    timestamptz
);

create unique index llm_api_keys_public_id_key on llm_api_keys (public_id);
-- The lookup the gateway's document is built from, and the guard against
-- issuing the same secret twice. Partial: keys awaiting issue have no hash,
-- and several of them at once is the ordinary state.
create unique index llm_api_keys_token_hash_key on llm_api_keys (token_hash)
    where token_hash is not null;
create index llm_api_keys_workspace_idx on llm_api_keys (workspace_id, status);
create index llm_api_keys_org_idx on llm_api_keys (org_id);

comment on column llm_api_keys.token_hash is
    '평문의 hex sha256. 평문은 발급 시 한 번만 표시하고 저장하지 않는다. 발급 전에는 null.';

-- One usage record per request the gateway served.
--
-- event_id is the gateway's idempotency key and is deliberately text, not
-- uuid: it is a UUIDv4 in the ordinary case, but the gateway falls back to a
-- timestamp-derived id if the system's random source fails, and a uuid column
-- would reject that row -- which would make the whole batch a permanent
-- failure and stall every later event behind it.
create table llm_usage_events (
    id                bigint generated always as identity primary key,
    event_id          text   not null,
    -- Null when the request never resolved to a key. Those rows are kept: they
    -- are the only trace of a client looping on a bad key.
    key_id            bigint references llm_api_keys (id),
    generation        bigint,
    public_model_name text,
    -- Which upstream actually served it, and how many attempts it took. The
    -- public model name hides the difference between a free local model and a
    -- paid fallback; without these the accounting cannot separate them, and
    -- nothing else records it.
    upstream_ref      text,
    attempts          int,
    status            text   not null,
    error_type        text,
    input_tokens      int    not null default 0,
    output_tokens     int    not null default 0,
    estimated         boolean not null default false,
    latency_ms        bigint not null default 0,
    ttft_ms           bigint,
    -- Order by this, never by arrival: a request that straddles UTC midnight
    -- reaches the api after events that happened later.
    requested_at      timestamptz not null,
    received_at       timestamptz not null default now()
);

create unique index llm_usage_events_event_id_key on llm_usage_events (event_id);
create index llm_usage_events_key_time_idx on llm_usage_events (key_id, requested_at desc);
create index llm_usage_events_time_idx on llm_usage_events (requested_at desc);

-- The generation the gateway polls against, in a single row.
--
-- A single row rather than a sequence on purpose: the writer takes a row lock
-- with `insert ... on conflict do update ... returning` before it touches keys
-- or models, which is what makes commit order and generation order agree. With
-- a sequence, a transaction holding the lower number can commit second, and a
-- poll at the higher generation reads a table that does not yet contain that
-- change -- which then never reaches the gateway at all, because nothing bumps
-- again.
--
-- The row is not seeded here. Migrations carry schema, not rows, so it comes
-- into existence the first time something writes it: the sync handler stamps
-- contact on every poll and the bump is an upsert, so both create it. Until
-- then "no row" is the honest state -- nothing has been configured and no
-- gateway has ever called.
create table llm_gateway_state (
    id               boolean primary key default true,
    generation       bigint  not null default 1,
    service_enabled  boolean not null default true,
    -- What the gateway last told us about itself. Claims, not measurements.
    applied_generation bigint,
    supported_format int,
    agent_version    text,
    started_at       timestamptz,
    in_flight        int,
    max_in_flight    int,
    upstream_refs    text,
    rejected_entries int,
    reload_failures  bigint,
    last_error       text,
    last_contact_at  timestamptz,
    contact_lost_since timestamptz,
    updated_at       timestamptz not null default now(),
    constraint llm_gateway_state_single_row check (id)
);

comment on table llm_gateway_state is
    '게이트웨이가 폴링하는 세대 카운터와, 게이트웨이가 스스로 보고한 상태.';

-- The approved-grant invariant, generalized past the VM.
--
-- V74 restored an invariant that had been lost once, and the loss was
-- invisible: an approved request whose granted specification was never written
-- simply sat there. The check it installed reads `resource_type = 'VM'`, so a
-- request of any other type passes it without being looked at -- which puts
-- every new type back in the state V74 was written to end.
--
-- The generalization keeps the VM's own rule and adds this type's: an approved
-- LLM key request must carry its detail row. It has no per-field granted
-- specification the way a VM does -- a key's limits may all be left at the
-- gateway's defaults -- so the row's existence is the whole assertion.
create or replace function assert_approved_request_is_granted() returns trigger
language plpgsql as $$
declare
    offending_request bigint;
begin
    select rv.request_id into offending_request
      from request_reviews rv
      join requests r on r.id = rv.request_id
      left join vm_request_details vd on vd.request_id = rv.request_id
      left join llm_key_request_details ld on ld.request_id = rv.request_id
     where rv.request_id = coalesce(new.request_id, old.request_id)
       and rv.decision = 'APPROVE'
       and case r.resource_type
               when 'VM' then vd.request_id is null
                    or vd.granted_vcpu is null or vd.granted_memory_mb is null
                    or vd.granted_disk_gb is null or vd.granted_image_id is null
               when 'LLM_API_KEY' then ld.request_id is null
               -- A type added later lands here. Refusing what we cannot check
               -- is what stops the next type from inheriting V74's silence.
               else true
           end;
    if offending_request is not null then
        raise exception 'approved request % has no complete granted specification', offending_request;
    end if;
    return null;
end $$;

drop trigger if exists trg_review_approve_needs_granted on request_reviews;
create constraint trigger trg_review_approve_needs_granted
    after insert or update on request_reviews
    deferrable initially deferred
    for each row execute function assert_approved_request_is_granted();

drop trigger if exists trg_detail_granted_matches_decision on vm_request_details;
create constraint trigger trg_detail_granted_matches_decision
    after insert or update or delete on vm_request_details
    deferrable initially deferred
    for each row execute function assert_approved_request_is_granted();

create constraint trigger trg_llm_detail_matches_decision
    after insert or update or delete on llm_key_request_details
    deferrable initially deferred
    for each row execute function assert_approved_request_is_granted();

drop function if exists assert_approved_vm_request_is_granted();
