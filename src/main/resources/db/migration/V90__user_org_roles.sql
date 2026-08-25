-- A person can administer more than one organisation.
--
-- users.org_id held the single organisation an org-tier account managed. It was
-- never an affiliation: chk_users_org_role made it null for every non-admin, so
-- a regular user's organisation has always been derived from the resources its
-- workspaces hold (OrgMembershipSql), and has always been able to span several.
-- Only the administrator side was pinned to one, and this table lifts that.
--
-- The row carries its own role, so an account may administer one organisation
-- and operate another. users.role stays and becomes the effective role: the
-- highest role held across these rows, or USER when none are left. That keeps
-- the @PreAuthorize gates, the JWT role claim and the permission matrix's role
-- columns as they are, with the annotation asking whether the account may ever
-- do a thing and the service layer asking whether it may here.
create table user_org_roles (
    user_id bigint not null references users (id) on delete cascade,
    org_id  bigint not null references orgs (id),
    role    user_role not null,
    primary key (user_id, org_id),
    constraint chk_user_org_roles_role
        check (role::text in ('ORG_ADMIN', 'ORG_MANAGER'))
);

comment on table user_org_roles is
    'Organisations an account administers, and its role in each. users.role is '
    'the highest role held here, or USER when the account holds none. That '
    'biconditional spans two tables and so is no longer a CHECK: the grant and '
    'revoke path is its single writer, and an org-tier account that somehow '
    'holds no row here fails closed (the scope resolvers answer 403).';

create index user_org_roles_org_id_idx on user_org_roles (org_id);

-- Landing transform, not a seed: every row here comes from the column being
-- dropped below. On a database with no org-tier account it writes nothing,
-- which is correct — the grant endpoint is what creates these rows.
insert into user_org_roles (user_id, org_id, role)
select id, org_id, role
from users
where org_id is not null;

alter table users drop constraint chk_users_org_role;
alter table users drop column org_id;
