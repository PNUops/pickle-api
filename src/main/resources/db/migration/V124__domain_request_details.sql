-- What a domain request asks for, and what approving it granted.
--
-- The label and the root are the whole of the ask: the organisation comes from
-- the root and the workspace is common to every request, so neither is stored
-- here. The granted columns are what approval decided, and for this kind the
-- decision has no dials — a reviewer approves the name or refuses it. So the
-- grant is the name itself, recorded as the fqdn that was actually issued.
-- That is not redundant with the ask: the root's default can move between
-- submission and approval, and the row has to say which name was created, not
-- which one was requested.
create table domain_request_details (
    request_id   bigint primary key references requests(id),
    label        text not null,
    root_domain  text not null,
    granted_fqdn text
);

comment on table domain_request_details is
    '외부 도메인 신청이 요청한 이름과 승인이 실제로 발급한 이름.';
comment on column domain_request_details.granted_fqdn is
    '승인이 발급한 이름. 신청 시점의 label과 root_domain을 이은 것과 다를 수 있다.';

-- Teach the approved-request guard about this kind.
--
-- Its unknown-type arm refuses rather than waves through, which means a kind
-- that never arrives here cannot be approved at all — the approval fails at
-- commit with no complete granted specification. That is the point of the arm
-- and it is why this block exists; V120 established the shape.
--
-- The assertion on the literal is doing real work: every future kind edits this
-- same function body by string replacement, so a rewrite that drops or reflows
-- the token would silently stop extending the guard rather than fail loudly.
--
-- It counts rather than merely looking, because replace() is global. One
-- occurrence is the invariant every kind so far has preserved by inserting its
-- arm in front of the single trailing `else true`; a second one appearing
-- somewhere unrelated would quietly graft this DOMAIN branch into that case
-- expression too, and the copy that landed in the wrong place would be as
-- silent as the missing token this check already refuses.
do $$
declare definition text;
begin
    definition := pg_get_functiondef('assert_approved_request_is_granted()'::regprocedure);
    if (length(definition) - length(replace(definition, 'else true', ''))) / length('else true') <> 1 then
        raise exception 'approved-request guard changed; review its DOMAIN branch before migrating';
    end if;
    definition := replace(definition, 'else true',
        'when ''DOMAIN'' then not exists (select 1 from domain_request_details dd where dd.request_id = rv.request_id and dd.granted_fqdn is not null) else true');
    execute definition;
end $$;

-- The mirror, as GPU has: a granted name with no approval behind it.
create or replace function assert_domain_detail_matches_decision() returns trigger language plpgsql as $$
declare rid bigint;
begin
    rid := coalesce(new.request_id, old.request_id);
    if exists (select 1 from domain_request_details d where d.request_id = rid
        and d.granted_fqdn is not null
        and not exists (select 1 from request_reviews rv where rv.request_id = rid and rv.decision = 'APPROVE')) then
        raise exception 'domain request % has a granted name without an approval', rid;
    end if;
    return null;
end $$;
create constraint trigger domain_detail_decision_check after insert or update or delete on domain_request_details
    deferrable initially deferred for each row execute function assert_domain_detail_matches_decision();
-- The forward guard on the detail table too, as GPU has it. Without this,
-- clearing granted_fqdn on an already-approved request succeeds silently: the
-- approved-request guard only fires on request_reviews writes, and there will
-- never be another one for that request.
create constraint trigger domain_detail_grant_check after insert or update or delete on domain_request_details
    deferrable initially deferred for each row execute function assert_approved_request_is_granted();
create constraint trigger domain_review_detail_check after insert or update or delete on request_reviews
    deferrable initially deferred for each row execute function assert_domain_detail_matches_decision();
