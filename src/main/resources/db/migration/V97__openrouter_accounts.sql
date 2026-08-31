-- Institution-owned OpenRouter business accounts and their management
-- credentials. Schema plus one landing transform only: existing funded or
-- provisioned keys are marked as belonging to the legacy env-managed source.
-- No account or credential row is environment-independent enough to seed.

create type openrouter_account_status as enum ('ACTIVE', 'ARCHIVED');
create type openrouter_credential_status as enum ('STAGED', 'ACTIVE', 'RETIRING');
create type openrouter_credential_error as enum
    ('CREDENTIAL_ERROR', 'THROTTLED', 'VENDOR_UNAVAILABLE', 'VENDOR_REJECTED');

create table openrouter_accounts (
    id                    bigint generated always as identity primary key,
    public_id             uuid not null default gen_random_uuid(),
    org_id                bigint not null references orgs (id),
    name                  text not null,
    status                openrouter_account_status not null default 'ACTIVE',
    funding_reference     text,
    evidence_reference    text,
    vendor_workspace_id   uuid,
    created_by            bigint not null references users (id),
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    constraint openrouter_accounts_name_nonblank check (btrim(name) <> ''),
    constraint openrouter_accounts_id_org_uq unique (id, org_id)
);

create unique index openrouter_accounts_public_id_uq
    on openrouter_accounts (public_id);
create unique index openrouter_accounts_org_name_uq
    on openrouter_accounts (org_id, lower(name));
create unique index openrouter_accounts_vendor_workspace_uq
    on openrouter_accounts (vendor_workspace_id)
    where vendor_workspace_id is not null;
create index openrouter_accounts_org_status_idx
    on openrouter_accounts (org_id, status, id);

create or replace function enforce_openrouter_account_identity_immutable()
returns trigger language plpgsql as $$
begin
    if new.org_id is distinct from old.org_id then
        raise exception 'OpenRouter account organisation is immutable';
    end if;
    if old.vendor_workspace_id is not null
       and new.vendor_workspace_id is distinct from old.vendor_workspace_id then
        raise exception 'OpenRouter vendor workspace is immutable once discovered';
    end if;
    return new;
end $$;

create trigger trg_openrouter_account_identity_immutable
    before update on openrouter_accounts
    for each row execute function enforce_openrouter_account_identity_immutable();

create table openrouter_account_credentials (
    id                           bigint generated always as identity primary key,
    account_id                   bigint not null references openrouter_accounts (id),
    status                       openrouter_credential_status not null,
    credential_enc               text not null,
    -- ACTIVE occupies slot 0; STAGED and RETIRING share rotation slot 1.
    -- The deferrable unique constraint permits the atomic slot swaps used by
    -- activation and rollback while still rejecting a second concurrent row.
    credential_slot              smallint generated always as
        (case when status = 'ACTIVE'::openrouter_credential_status then 0 else 1 end) stored,
    created_by                   bigint not null references users (id),
    created_at                   timestamptz not null default now(),
    last_verification_attempt_at timestamptz,
    verified_at                  timestamptz,
    activated_at                 timestamptz,
    retiring_at                  timestamptz,
    last_used_at                 timestamptz,
    last_reconciled_at           timestamptz,
    verification_error           openrouter_credential_error,
    constraint openrouter_account_credentials_slot_uq
        unique (account_id, credential_slot) deferrable initially immediate,
    constraint openrouter_account_credentials_state_check check (
        (status <> 'ACTIVE'::openrouter_credential_status or activated_at is not null)
        and (status <> 'RETIRING'::openrouter_credential_status or retiring_at is not null))
);

create index openrouter_account_credentials_account_status_idx
    on openrouter_account_credentials (account_id, status);

alter table llm_api_keys
    add column openrouter_account_id bigint,
    -- TRUE is the expand/rollback default: an old jar cannot write the new
    -- account column, so its positive-credit inserts must keep landing on the
    -- legacy source during the rollback window. New positive approvals write
    -- an account binding with false, while new unbound credit-0 rows stay true.
    -- A later contract migration flips or removes this default after old-jar
    -- rollback is no longer supported.
    add column openrouter_legacy boolean not null default true;

-- Every account-null row stays in the rollback set. A rolled-back old jar can
-- raise a token-only row from credit 0 to positive but cannot populate the new
-- account column; marking the whole set is what keeps that update compatible.
update llm_api_keys
   set openrouter_legacy = true
 where openrouter_account_id is null;

alter table llm_api_keys
    add constraint llm_api_keys_openrouter_account_org_fk
        foreign key (openrouter_account_id, org_id)
        references openrouter_accounts (id, org_id),
    add constraint llm_api_keys_openrouter_pair_check check (
        (openrouter_key_hash is null) = (openrouter_key_enc is null)),
    add constraint llm_api_keys_openrouter_source_check check (
        not (openrouter_account_id is not null and openrouter_legacy)),
    add constraint llm_api_keys_positive_credit_source_check check (
        credit_limit <= 0 or openrouter_account_id is not null or openrouter_legacy);

create unique index llm_api_keys_openrouter_account_hash_uq
    on llm_api_keys (openrouter_account_id, openrouter_key_hash)
    where openrouter_account_id is not null and openrouter_key_hash is not null;
create unique index llm_api_keys_openrouter_legacy_hash_uq
    on llm_api_keys (openrouter_key_hash)
    where openrouter_legacy and openrouter_key_hash is not null;
create index llm_api_keys_openrouter_account_pending_idx
    on llm_api_keys (openrouter_account_id, id)
    where credit_limit > 0 and openrouter_key_hash is null;

create or replace function enforce_llm_key_openrouter_binding_immutable()
returns trigger language plpgsql as $$
begin
    if old.openrouter_account_id is not null then
        if new.openrouter_account_id is distinct from old.openrouter_account_id
           or new.openrouter_legacy then
            raise exception 'OpenRouter account binding is immutable';
        end if;
    elsif old.openrouter_legacy then
        if new.openrouter_legacy then
            if new.openrouter_account_id is not null then
                raise exception 'legacy and account OpenRouter sources are mutually exclusive';
            end if;
        elsif new.openrouter_account_id is not null
              and (new.openrouter_key_hash is not null
                   or new.openrouter_key_enc is not null) then
            raise exception 'provisioned legacy OpenRouter key cannot be rebound';
        elsif new.openrouter_account_id is null
              and not (new.credit_limit <= 0
                       and new.openrouter_key_hash is null
                       and new.openrouter_key_enc is null) then
            raise exception 'legacy OpenRouter source may only transition to an account';
        end if;
    elsif new.openrouter_legacy then
        raise exception 'only the landing transform may create a legacy OpenRouter binding';
    end if;
    return new;
end $$;

create trigger trg_llm_key_openrouter_binding_immutable
    before update of openrouter_account_id, openrouter_legacy on llm_api_keys
    for each row execute function enforce_llm_key_openrouter_binding_immutable();

alter table llm_key_request_details
    add column granted_openrouter_account_id bigint
        references openrouter_accounts (id);

create or replace function enforce_llm_request_openrouter_account()
returns trigger language plpgsql as $$
declare
    request_org_id bigint;
    account_org_id bigint;
begin
    if tg_op = 'UPDATE'
       and old.granted_openrouter_account_id is not null
       and new.granted_openrouter_account_id is distinct from old.granted_openrouter_account_id then
        raise exception 'granted OpenRouter account binding is immutable';
    end if;
    if new.granted_openrouter_account_id is null then
        -- A V96 jar cannot write this new column. Its approval transaction is
        -- still safe to roll back to because it materializes the matching key
        -- with openrouter_legacy=true before this deferred trigger runs.
        if new.granted_credit_limit is not null and new.granted_credit_limit > 0
           and not exists (
               select 1
                 from llm_api_keys k
                where k.request_id = new.request_id
                  and k.openrouter_legacy) then
            raise exception 'positive granted credit requires an OpenRouter account';
        end if;
        return new;
    end if;
    select org_id into request_org_id from requests where id = new.request_id;
    select org_id into account_org_id from openrouter_accounts
     where id = new.granted_openrouter_account_id;
    if request_org_id is distinct from account_org_id then
        raise exception 'granted OpenRouter account must belong to the request organisation';
    end if;
    return new;
end $$;

create constraint trigger trg_llm_request_openrouter_account
    after insert or update on llm_key_request_details
    deferrable initially deferred
    for each row execute function enforce_llm_request_openrouter_account();

comment on table openrouter_accounts is
    '기관별 OpenRouter 사업·재원·결제 단위. vendor workspace는 key 정책 범위이며 결제 account를 대신하지 않는다.';
comment on table openrouter_account_credentials is
    'Account management credential의 암호문과 staged overlap 상태. 평문·hash·prefix·masked label은 저장하지 않는다.';
comment on column llm_api_keys.openrouter_legacy is
    'Account-null key의 old-jar rollback transition 표식. DB default와 account-null 신규 행은 true이고 remote key가 없는 최초 금액 binding 시 false가 된다. Provisioned legacy 행은 새 Pickle key로 전환하며, 0-credit·remote 없음 행의 false 전환은 old jar가 rollback set에서 빠진 뒤의 후속 cutover 전용이다.';
