-- Terminology standardization (2026-07-16, docs/glossary.md): the general
-- platform role is 사용자/USER, not 학생/STUDENT. Dev-only data, irreversible —
-- take a manual DB backup before deploying (the deploy-api.sh health-check
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
