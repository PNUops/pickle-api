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

create type vm_actor_kind as enum ('SYSTEM', 'MEMBER', 'ADMIN');

alter table vm_events add column actor_kind vm_actor_kind;

-- Existing rows predate the stamp, so their kind is INFERRED, not recorded:
-- an actor who is still a member of the VM's workspace is read as MEMBER,
-- anyone else as an administrator who reached in from outside. That inference
-- is right for the rows this schema has (administrators are not members of the
-- workspaces they intervene in) and is made exactly once, here. Every row
-- written from now on carries what the code stamped, not what a query guessed.
update vm_events e
   set actor_kind = case
           when e.actor_id is null then 'SYSTEM'::vm_actor_kind
           when exists (select 1
                          from vms v
                          join workspace_members m on m.workspace_id = v.workspace_id
                         where v.id = e.vm_id
                           and m.user_id = e.actor_id) then 'MEMBER'::vm_actor_kind
           else 'ADMIN'::vm_actor_kind
       end;

alter table vm_events alter column actor_kind set not null;

-- The two columns answer the same question from different sides, so a row that
-- disagrees with itself must not be insertable.
alter table vm_events add constraint vm_events_actor_kind_ck
    check ((actor_id is null) = (actor_kind = 'SYSTEM'));
