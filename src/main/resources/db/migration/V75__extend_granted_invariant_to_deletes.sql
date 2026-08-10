-- Extends the invariant V74 restored so that removing the detail row is judged
-- the same as never writing it.
--
-- V74 declared its triggers for insert and update only, which leaves the rule
-- with an exit: deleting an approved request's detail row drops the granted
-- specification and nothing complains. No code path deletes that row today, so
-- this closes a hole rather than a bug -- but a guard that one statement can
-- step around is not the guard V74 says it is.
--
-- The function already reads coalesce(new.request_id, old.request_id), so it
-- needs no change; only the trigger is redeclared. Deleting a request outright
-- is not something this has to allow for: neither child table cascades, so the
-- foreign keys refuse the delete before any trigger runs. Verified on a copy of
-- the dev database -- the same delete that silently emptied an approved
-- request's specification before this file now raises, and the row survives.

drop trigger if exists trg_detail_granted_matches_decision on vm_request_details;

create constraint trigger trg_detail_granted_matches_decision
    after insert or update or delete on vm_request_details
    deferrable initially deferred
    for each row execute function assert_approved_vm_request_is_granted();
