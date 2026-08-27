-- Which surface performed a VM event, stamped at write time, so the VM's own
-- history can name a workspace member and still keep an administrator's
-- identity out of an end user's screen.
--
-- Until now vm_events carried actor_id and nothing else, so the history could
-- only say "somebody" or "nobody" — every human action rendered identically
-- whether a teammate stopped the VM or an administrator intervened.
--
-- The distinction is NOT the actor's role. An account's effective role is the
-- highest one it holds anywhere, so a department's operator doing ordinary
-- work in their own workspace would be stamped as an intervention. What the
-- record needs is the surface the action came through: the admin endpoints
-- write ADMIN, the member endpoints write MEMBER, the background jobs write
-- SYSTEM. Same reasoning as audit_logs.actor_role — a fact about the moment,
-- fixed at the moment, not re-derived later from state that keeps moving.

create type vm_actor_kind as enum ('SYSTEM', 'MEMBER', 'ADMIN', 'UNKNOWN');

-- The default lets the column land without rewriting the table (PG 11+ keeps
-- it in the catalogue), so only the rows the backfill actually decides are
-- written. It is dropped again below: an insert that forgets the kind must
-- fail, not inherit a wrong one.
alter table vm_events add column actor_kind vm_actor_kind not null default 'SYSTEM';

-- Existing rows predate the stamp, so their kind is RECOVERED where the event
-- type decides it and left UNKNOWN where nothing does.
--
-- The type decides it whenever only one surface can write that type: the six
-- admin-only types below are reachable through the admin endpoints alone, and
-- PUBLISH and PORT_FORWARD_CREATE only through the member ones. What is left
-- is genuinely ambiguous — power actions, deletions and UNPUBLISH each have a
-- member endpoint and an admin one.
--
-- For those, current workspace membership is used in ONE direction only. An
-- actor who is still a member is read as a member: their name is already on
-- the workspace's member list, so nothing is disclosed by it. An actor who is
-- not is left UNKNOWN rather than read as an administrator, because leaving a
-- workspace deletes the membership row — inferring ADMIN from its absence
-- would rewrite a departed colleague's ordinary work into an intervention
-- that never happened, in a history that is append-only. UNKNOWN renders the
-- way this history read before this column existed: a human acted, unnamed.
update vm_events e
   set actor_kind = case
           when e.type in ('SCHEDULE_DELETE', 'CANCEL_SCHEDULED_DELETE', 'FORCE_DELETE',
                           'PERIOD_UPDATE', 'GATEWAY_BLOCK', 'GATEWAY_UNBLOCK')
               then 'ADMIN'::vm_actor_kind
           when e.type in ('PUBLISH', 'PORT_FORWARD_CREATE') then 'MEMBER'::vm_actor_kind
           when exists (select 1
                          from vms v
                          join workspace_members m on m.workspace_id = v.workspace_id
                         where v.id = e.vm_id
                           and m.user_id = e.actor_id) then 'MEMBER'::vm_actor_kind
           else 'UNKNOWN'::vm_actor_kind
       end
 where e.actor_id is not null;

alter table vm_events alter column actor_kind drop default;

-- The two columns answer the same question from different sides, so a row that
-- disagrees with itself must not be insertable. NOT VALID keeps the exclusive
-- lock off the scan; the validation that follows takes a weaker one.
alter table vm_events add constraint vm_events_actor_kind_ck
    check ((actor_id is null) = (actor_kind = 'SYSTEM')) not valid;
alter table vm_events validate constraint vm_events_actor_kind_ck;
