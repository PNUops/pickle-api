-- Terminology standardization (2026-07-16): the SYS_ADMIN
-- immediate deletion is 강제 삭제/FORCE, symmetric with the power-control
-- FORCE_STOP — the old EMERGENCY naming is retired. Dev-only data,
-- irreversible — take a manual DB backup before deploying (the deploy
-- health-check auto-rollback restores the OLD jar on top of the renamed
-- enums, which breaks immediately: fix forward instead).
--
-- RENAME VALUE is fully transactional (PG10+); rows store enum OIDs, so
-- existing data follows automatically.

alter type vm_delete_kind rename value 'EMERGENCY'        to 'FORCE';
alter type vm_event_type  rename value 'EMERGENCY_DELETE' to 'FORCE_DELETE';

-- notifications.event stores the dot-namespaced catalog id as text.
update notifications set event = 'vm.delete.force' where event = 'vm.delete.emergency';

-- audit_logs.action key follows the rename. V7 made the table append-only by
-- REVOKE (including from the executing role); the owner may deliberately
-- re-grant itself (V7's documented intentional-change path). Zonky tests run
-- as superuser and bypass ACLs either way. Backfill approved by the operator
-- 2026-07-16 (dev/test data only); display texts (title/body/detail) are
-- point-in-time snapshots and stay untouched.
do $$ begin
  execute format('grant update on audit_logs to %I', current_user);
end $$;
update audit_logs set action = 'vm.force_delete' where action = 'vm.emergency_delete';
do $$ begin
  execute format('revoke update on audit_logs from %I', current_user);
end $$;

-- Terminology standardization (2026-07-16): the general
-- platform role is 사용자/USER, not 학생/STUDENT. Dev-only data, irreversible —
-- take a manual DB backup before deploying (the deploy health-check
-- auto-rollback restores the OLD jar on top of the renamed enum, which breaks
-- immediately: fix forward instead).
--
-- RENAME VALUE is fully transactional (PG10+); rows store enum OIDs, so
-- existing data and the users.role column default follow automatically.

alter type user_role rename value 'STUDENT' to 'USER';

-- The stored default already follows the rename (enum-OID constant); restate
-- it explicitly so \d users reads unambiguously.
alter table users alter column role set default 'USER';

-- audit_logs.actor_role is a text snapshot of the actor's role name — follow
-- the rename so future queries/UI need only one spelling. V7 made the table
-- append-only by REVOKE (including from the executing role); the owner may
-- deliberately re-grant itself (V7's documented intentional-change path).
-- Zonky tests run as superuser and bypass ACLs either way. Backfill approved
-- by the operator 2026-07-16 (dev/test data only).
do $$ begin
  execute format('grant update on audit_logs to %I', current_user);
end $$;
update audit_logs set actor_role = 'USER' where actor_role = 'STUDENT';
do $$ begin
  execute format('revoke update on audit_logs from %I', current_user);
end $$;

-- Terminology standardization (2026-07-16): the group role
-- between OWNER and MEMBER is 편집자/EDITOR — the old MANAGER naming is
-- retired to keep it distinct from the future org/system 운영자 roles
-- (ORG_MANAGER/SYS_MANAGER). RENAME VALUE is fully transactional (PG10+);
-- rows store enum OIDs, so existing data follows automatically.

alter type group_member_role rename value 'MANAGER' to 'EDITOR';

-- Terminology standardization (2026-07-16): audit action
-- keys follow `domain.verb_snake` with a single dot — vm.password.reveal was
-- the only outlier. V7 made audit_logs append-only by REVOKE; the owner may
-- deliberately re-grant itself (V7's documented intentional-change path).
-- Backfill approved by the operator 2026-07-16 (dev/test data only).

do $$ begin
  execute format('grant update on audit_logs to %I', current_user);
end $$;
update audit_logs set action = 'vm.password_reveal' where action = 'vm.password.reveal';
do $$ begin
  execute format('revoke update on audit_logs from %I', current_user);
end $$;
