-- Restores the invariant V72 dropped: an APPROVE decision must carry a
-- complete granted specification.
--
-- V11 put that rule in a CHECK on the review row, where the decision and the
-- granted values sat in the same table. V72 moved the specification to the
-- per-type detail row and left behind a CHECK that only forbids a *partial*
-- specification -- it cannot see the decision at all, because a CHECK cannot
-- read another table. The comment written above it claimed otherwise, which is
-- the more serious half of the defect: it read as though the rule survived.
--
-- The rule now spans two tables, so it needs a constraint trigger. Deferred to
-- commit because the approval transaction writes the review row before the
-- detail row's granted values, and a row-immediate check would fire in the gap.
-- Both tables carry it: whichever side is written last, the pair is judged.

create or replace function assert_approved_vm_request_is_granted() returns trigger
language plpgsql as $$
declare
    offending_request bigint;
begin
    select rv.request_id into offending_request
      from request_reviews rv
      join requests r on r.id = rv.request_id
      left join vm_request_details d on d.request_id = rv.request_id
     where rv.request_id = coalesce(new.request_id, old.request_id)
       and rv.decision = 'APPROVE'
       and r.resource_type = 'VM'
       and (d.request_id is null
            or d.granted_vcpu is null or d.granted_memory_mb is null
            or d.granted_disk_gb is null or d.granted_image_id is null);
    if offending_request is not null then
        raise exception 'approved VM request % has no complete granted specification',
            offending_request;
    end if;
    return null;
end $$;

create constraint trigger trg_review_approve_needs_granted
    after insert or update on request_reviews
    deferrable initially deferred
    for each row execute function assert_approved_vm_request_is_granted();

create constraint trigger trg_detail_granted_matches_decision
    after insert or update on vm_request_details
    deferrable initially deferred
    for each row execute function assert_approved_vm_request_is_granted();

comment on constraint chk_vm_request_details_approved_granted on vm_request_details is
    '부여 사양은 전부 있거나 전부 없어야 한다. "승인이면 반드시 있다"는 두 테이블에 걸친 규칙이라 이 검사가 아니라 지연 제약 트리거가 지킨다.';
