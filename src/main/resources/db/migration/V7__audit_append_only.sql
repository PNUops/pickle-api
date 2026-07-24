-- Append-only audit_logs, enforced at the DB level: the table is append-only
-- (the app DB role lacks UPDATE/DELETE on it). Previously
-- left to ops (see the V2 comment); this migration itself revokes
-- row-mutation privileges from everyone, including the executing role.
--
-- Effect by environment:
-- * zonky embedded tests run migrations as the `postgres` SUPERUSER, which
--   bypasses ACLs entirely: the revokes are harmless there but the constraint
--   is NOT enforced (tests cannot exercise it).
-- * dev/prod run as the `pickle` role, which owns audit_logs: revoking the
--   owner's own UPDATE/DELETE/TRUNCATE is effective and those statements now
--   fail. The owner can still re-GRANT itself the privileges, but that takes
--   a deliberate act — the boundary is "no accidental/implicit mutation", not
--   protection against a malicious owner. Post-deploy verification is in the
--   operator runbook.
--
-- current_user is resolved dynamically because the executing role differs per
-- environment (postgres in zonky, pickle in dev/prod).

revoke update, delete, truncate on audit_logs from public;
do $$ begin
  execute format('revoke update, delete, truncate on audit_logs from %I', current_user);
end $$;
