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
