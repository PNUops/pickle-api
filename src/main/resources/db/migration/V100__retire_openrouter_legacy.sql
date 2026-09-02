-- Retires the legacy OpenRouter source.
--
-- V97 kept `openrouter_legacy` so a jar that predated the account model could
-- still land positive-credit keys while a rollback to it was possible. That
-- window closed on 2026-09-01: institution accounts are registered and funded,
-- the global management key is gone from the vault and from the container, the
-- rollout switch is on, and a key bound to an account has served real traffic.
-- With the key gone the legacy branch is unreachable code -- `forKey` returns
-- empty for a legacy row because the environment value it needs is unset -- so
-- the column now only describes rows nothing can act on.
--
-- V97:88 said a later contract migration would flip or remove the default. This
-- is that migration, and it removes rather than flips: there is no longer a
-- source for the value to default to.
--
-- This is a contract migration, not an expand. `LlmApiKey` maps the column as
-- non-null, so the jar running before this one cannot start against the result.
-- Take a database backup point first; there is no rollback.

-- 0. Everything below reads `llm_api_keys` to decide what is safe, and the
--    last step rewrites its shape. Without this lock those two see different
--    databases: the check in step 1b takes its own READ COMMITTED snapshot,
--    neither it nor the delete blocks an insert, and `drop column` does not
--    take ACCESS EXCLUSIVE until it runs at the end. A legacy row committed by
--    another session in that window is invisible to the check and present
--    after the drop, which is the one row the check exists to stop. That is
--    not theoretical here: another session created keys in this table while
--    this migration was being written.
lock table llm_api_keys in access exclusive mode;

-- 1. The revoked legacy rows. The operator confirmed the whole deployment is
--    test data during this period; step 1b decides what may survive.
--    The predicate is descriptive rather than a list of ids so that a database
--    which never carried them simply matches nothing.
--
--    Children first: none of the three foreign keys carries an on-delete
--    action, so a bare delete on the parent would fail rather than cascade.
delete from llm_credit_usage_snapshots
 where key_id in (select id from llm_api_keys
                   where openrouter_legacy and status = 'REVOKED');
delete from llm_usage_daily
 where key_id in (select id from llm_api_keys
                   where openrouter_legacy and status = 'REVOKED');
delete from llm_usage_events
 where key_id in (select id from llm_api_keys
                   where openrouter_legacy and status = 'REVOKED');
delete from llm_api_keys
 where openrouter_legacy and status = 'REVOKED';

-- 1b. The premise, checked rather than assumed. Step 1 deletes only revoked
--     rows, so a live legacy row survives it and simply loses the marker when
--     the column goes. For most of them that is the intended cutover and not
--     a loss: a row with no money and no vendor key is an ordinary unbound
--     token-only key afterwards, and can still take its first account later.
--
--     Two shapes are not that, and the drop would weaken them silently. A
--     surviving row holding a vendor key loses the legacy-scoped hash
--     uniqueness (the account-scoped index has no bearing on an account-null
--     row), becomes unreachable by every management path because `forKey`
--     answers empty without an account, and can no longer be bound at all. A
--     surviving row with a positive limit would fail step 5 anyway, and
--     saying why here beats a bare constraint violation.
--
--     Either way the answer is a decision about a key someone is using, so
--     stop and let a person make it. Measured on the live database on
--     2026-09-02: thirteen marked rows, eleven revoked and two live, and both
--     live ones are the harmless shape (no money, no vendor key).
do $$
declare
    stranded bigint;
    ids text;
begin
    -- The status filter is not redundant with step 1. Without it this reads
    -- "any marked row", which is only the same set because the delete ran
    -- first, and a check that depends on a neighbour's ordering to mean what
    -- it says is a check that will eventually mean something else.
    select count(*), string_agg(public_id::text, ', ' order by id)
      into stranded, ids
      from llm_api_keys
     where openrouter_legacy
       and status <> 'REVOKED'
       and (credit_limit > 0
            or openrouter_key_hash is not null
            or openrouter_key_enc is not null);
    if stranded > 0 then
        -- Naming them matters: this message is the only output this block
        -- ever produces, and it reaches someone whose deployment just
        -- stopped. A bare count leaves them writing the query themselves.
        raise exception
            'V100 refuses to run: % live legacy OpenRouter key(s) hold money '
            'or a vendor key (%). Revoke them, or reissue them under an '
            'account, before retiring the legacy source.', stranded, ids;
    end if;
end $$;

-- 2. The binding trigger, rewritten without the legacy branches. What survives
--    is the rule that matters: once a key names the account that funds it, that
--    naming cannot change. Money already spent under one account cannot be made
--    to look like another account's spend, so a key moves by being revoked and
--    reissued rather than repointed.
drop trigger if exists trg_llm_key_openrouter_binding_immutable on llm_api_keys;

create or replace function enforce_llm_key_openrouter_binding_immutable()
returns trigger language plpgsql as $$
begin
    if old.openrouter_account_id is not null
       and new.openrouter_account_id is distinct from old.openrouter_account_id then
        raise exception 'OpenRouter account binding is immutable';
    end if;
    -- An unbound key may still take its first account, but not once a vendor
    -- key exists under the old source: that key was issued somewhere, and
    -- repointing the row would leave the spend behind.
    if old.openrouter_account_id is null
       and new.openrouter_account_id is not null
       and (old.openrouter_key_hash is not null or old.openrouter_key_enc is not null) then
        raise exception 'provisioned OpenRouter key cannot be rebound';
    end if;
    return new;
end $$;

create trigger trg_llm_key_openrouter_binding_immutable
    before update of openrouter_account_id on llm_api_keys
    for each row execute function enforce_llm_key_openrouter_binding_immutable();

-- 3. The request-side check. This one is why the column cannot simply be
--    dropped: plpgsql resolves column names when the function runs, so
--    `pg_depend` does not know this body reads `openrouter_legacy`, and neither
--    `drop column` nor `drop column ... cascade` complains. Dropping without
--    rewriting leaves a migration that reports success and a function that
--    breaks later, on one path.
--
--    Measured on a clone of the live database rather than reasoned about. The
--    body short-circuits, so the dead column is only reached when an approval
--    writes a positive money grant with no account -- and that is a path users
--    take: it is the approval the console makes when the approver has not
--    picked an account yet. With the old body and the column gone it answers
--    `column k.openrouter_legacy does not exist` instead of the refusal the
--    approver is meant to read. Approvals that do name an account commit fine,
--    which is what makes it easy to test the wrong path and conclude nothing
--    is wrong.
--
--    Removing the escape makes the rule unconditional: a positive money grant
--    names the account that funds it. Three approvals from before the account
--    model hold a positive grant with no account. They are terminal -- only an
--    approval writes this table, and theirs already happened -- and the request
--    rows stay because the retention model keeps them. They are left as they
--    are rather than rewritten to say something that was not true at the time.
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
        if new.granted_credit_limit is not null and new.granted_credit_limit > 0 then
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

-- 4. The objects that named the column. The account-scoped uniqueness stays;
--    only the legacy-scoped partial index goes, and with the legacy rows gone
--    there is no unbound row left holding a vendor key hash for it to protect.
drop index if exists llm_api_keys_openrouter_legacy_hash_uq;
alter table llm_api_keys
    drop constraint if exists llm_api_keys_openrouter_source_check,
    drop constraint if exists llm_api_keys_positive_credit_source_check;

alter table llm_api_keys drop column openrouter_legacy;

-- 5. The invariant, restated without the escape. Step 1 removed every row that
--    would have violated it, so this is stricter than what it replaces: a key
--    that can spend money always names the account the money comes from.
alter table llm_api_keys
    add constraint llm_api_keys_positive_credit_source_check check (
        credit_limit <= 0 or openrouter_account_id is not null);

-- 6. V97's own comments are frozen bytes and two of them are now false. A
--    migration cannot edit an applied one, so the correction rides here.
comment on table openrouter_accounts is
    '기관별 OpenRouter 사업·결제 단위. vendor workspace는 key 정책 범위이며 결제 account를 대신하지 않는다. '
    '2026-09-01부터 이것이 유일한 관리 자격증명 출처다. 전역 env 키를 쓰던 legacy 경로는 V100이 없앴다.';
comment on column llm_api_keys.openrouter_account_id is
    '이 key의 상용 지출을 대는 기관 사업 account. 금액 한도가 0보다 크면 반드시 있어야 하고 한 번 정해지면 바뀌지 않는다. '
    'V97이 두던 legacy 대체 출처는 V100이 없앴다.';
