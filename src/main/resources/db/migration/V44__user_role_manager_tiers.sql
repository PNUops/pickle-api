-- Operator tiers: two reduced-permission roles
-- below the admin roles — ORG_MANAGER (기관 운영자, below ORG_ADMIN) and
-- SYS_MANAGER (시스템 운영자, below SYS_ADMIN). Adds the enum values and widens
-- the users.org_id ⇔ role invariant so ORG_MANAGER, like ORG_ADMIN, carries a
-- managed org.
--
-- PG rule (see V21 note): a value added by ALTER TYPE ... ADD VALUE cannot be
-- *used* as an enum value in the same transaction that added it. Flyway runs
-- this migration in one transaction, so the rewritten CHECK compares role::text
-- against text literals — the new 'ORG_MANAGER' value is never materialised as
-- an enum here — keeping the whole change atomic (V21 precedent: a single
-- transactional migration, no non-transactional escape hatch needed).

alter type user_role add value if not exists 'ORG_MANAGER';
alter type user_role add value if not exists 'SYS_MANAGER';

-- users.org_id is set iff the role is an org-tier admin (ORG_ADMIN or the new
-- ORG_MANAGER). Replaces the V11 ORG_ADMIN-only form (chk_users_org_role); the
-- service layer (AdminService.updateUser) remains the single writer and mirrors
-- this invariant (UserRole.isOrgTier()).
alter table users drop constraint chk_users_org_role;
alter table users
    add constraint chk_users_org_role
    check ((role::text in ('ORG_ADMIN', 'ORG_MANAGER')) = (org_id is not null));
