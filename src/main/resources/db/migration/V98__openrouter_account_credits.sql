-- Cached account-wide OpenRouter credit readings, durable account poll claims,
-- and immutable paired observation windows. No environment row is seeded:
-- each account establishes its baseline on the first successful paired poll.

alter table openrouter_accounts
    add column vendor_identity_key_hash text,
    add column credits_total numeric(14, 6),
    add column credits_usage numeric(14, 6),
    add column credits_observed_at timestamptz,
    add column credits_last_success_at timestamptz,
    add column credits_last_attempt_at timestamptz,
    add column credits_error openrouter_credential_error,
    add column credits_failure_count integer not null default 0,
    add column credits_next_due_at timestamptz,
    add column credits_not_before_at timestamptz,
    add column keys_failure_count integer not null default 0,
    add column keys_next_due_at timestamptz,
    add column keys_not_before_at timestamptz,
    add column keys_last_success_at timestamptz,
    add column keys_last_attempt_at timestamptz,
    add column keys_error openrouter_credential_error,
    add column credits_refresh_requested_at timestamptz,
    add column full_refresh_requested_at timestamptz,
    add column last_triggered_refresh_at timestamptz,
    add column poll_claim_token uuid,
    add column poll_claim_until timestamptz,
    add column poll_claim_credential_id bigint,
    add column poll_claim_kind text,
    add column poll_window_started_at timestamptz,
    add column poll_claim_credits_request_at timestamptz,
    add column poll_claim_full_request_at timestamptz,
    add column paired_window_id uuid,
    add column paired_total_usage numeric(14, 6),
    add column paired_managed_usage numeric(14, 6),
    add column paired_credits_observed_at timestamptz,
    add column paired_keys_observed_at timestamptz,
    add column spend_baseline_total_usage numeric(14, 6),
    add column spend_baseline_managed_usage numeric(14, 6),
    add column spend_baseline_observed_at timestamptz,
    add column spend_baseline_invalidated_at timestamptz,
    add constraint openrouter_accounts_credits_pair_check check (
        (credits_total is null) = (credits_usage is null)
        and (credits_total is null) = (credits_observed_at is null)
        and (credits_total is null) = (credits_last_success_at is null)),
    add constraint openrouter_accounts_paired_usage_check check (
        (paired_window_id is null) = (paired_total_usage is null)
        and (paired_window_id is null) = (paired_managed_usage is null)
        and (paired_window_id is null) = (paired_credits_observed_at is null)
        and (paired_window_id is null) = (paired_keys_observed_at is null)),
    add constraint openrouter_accounts_spend_baseline_check check (
        (spend_baseline_total_usage is null) =
            (spend_baseline_managed_usage is null)
        and (spend_baseline_total_usage is null) =
            (spend_baseline_observed_at is null)),
    add constraint openrouter_accounts_poll_claim_check check (
        (poll_claim_token is null) = (poll_claim_until is null)
        and (poll_claim_token is null) = (poll_claim_credential_id is null)
        and (poll_claim_token is null) = (poll_claim_kind is null)
        and (poll_claim_token is null) = (poll_window_started_at is null)
        and (poll_claim_kind is null or poll_claim_kind in ('CREDITS', 'PAIR'))),
    add constraint openrouter_accounts_poll_failure_count_check check (
        credits_failure_count >= 0 and keys_failure_count >= 0);

create index openrouter_accounts_poll_due_idx
    on openrouter_accounts (credits_next_due_at, keys_next_due_at, id)
    where status = 'ACTIVE'::openrouter_account_status;
create index openrouter_accounts_poll_claim_idx
    on openrouter_accounts (poll_claim_until, id)
    where poll_claim_token is not null;
create unique index openrouter_accounts_vendor_identity_key_uq
    on openrouter_accounts (vendor_identity_key_hash)
    where vendor_identity_key_hash is not null;

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
    if old.vendor_identity_key_hash is not null
       and new.vendor_identity_key_hash is distinct from old.vendor_identity_key_hash then
        raise exception 'OpenRouter vendor billing identity is immutable once established';
    end if;
    return new;
end $$;

create table openrouter_credit_snapshots (
    id                           bigint generated always as identity primary key,
    account_id                   bigint not null references openrouter_accounts (id),
    observation_id               uuid not null,
    total_credits                numeric(14, 6) not null,
    total_usage                  numeric(14, 6) not null,
    managed_usage_since_baseline numeric(14, 6),
    unmanaged_usage              numeric(14, 6),
    window_started_at            timestamptz not null,
    credits_observed_at          timestamptz not null,
    keys_observed_at             timestamptz,
    window_completed_at          timestamptz not null,
    constraint openrouter_credit_snapshots_observation_uq unique (observation_id),
    constraint openrouter_credit_snapshots_pair_check check (
        (managed_usage_since_baseline is null) = (keys_observed_at is null)
        and (unmanaged_usage is null or keys_observed_at is not null))
);

create index openrouter_credit_snapshots_account_time_idx
    on openrouter_credit_snapshots (account_id, credits_observed_at desc);
create index openrouter_credit_snapshots_paired_time_idx
    on openrouter_credit_snapshots (account_id, credits_observed_at desc)
    where keys_observed_at is not null;

alter table llm_api_keys
    add column openrouter_limit_remaining numeric(14, 6),
    add column openrouter_accounted_usage numeric(14, 6) not null default 0;

comment on table openrouter_credit_snapshots is
    'Account /credits history. keys_observed_at가 있으면 같은 claim window의 /keys paired observation이다.';
comment on column llm_api_keys.openrouter_accounted_usage is
    'Account baseline 뒤 reset-aware managed usage 누계. Key 삭제 뒤에도 보존해 미관리 지출을 과대계상하지 않는다.';
comment on column openrouter_accounts.poll_claim_credential_id is
    'Job 인자에 secret을 넣지 않기 위한 ACTIVE credential id CAS. Credential rotation 뒤 stale 완료는 버린다.';
