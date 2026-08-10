-- The owning unit is renamed from group to workspace, everywhere at once.
--
-- "Group" described a set of people, but the object it names is what owns and
-- scopes resources — a workspace holding VMs today and containers, GPU shares
-- and LLM API keys next. The name is also about to spread further than it has
-- ever been: the console gains a workspace-scoped view of every resource, so a
-- display-only rename would leave the code calling one thing by a name the
-- product no longer uses.
--
-- This file renames and nothing else. The only rows it writes are the two
-- announcement scope labels, which re-express state already here rather than
-- stating any fact about the environment.

alter table groups rename to workspaces;
alter table group_members rename to workspace_members;
alter table workspace_members rename column group_id to workspace_id;

alter type group_kind rename to workspace_kind;
alter type group_member_role rename to workspace_member_role;

alter table vm_requests rename column group_id to workspace_id;
alter table vms rename column group_id to workspace_id;
alter table announcements rename column group_id to workspace_id;

-- Renaming a table leaves its indexes and constraints on their old names, so
-- every name a later migration or an error message could quote is renamed too.
alter table workspaces rename constraint groups_pkey to workspaces_pkey;
alter index groups_slug_live_uniq rename to workspaces_slug_live_uniq;

alter table workspace_members rename constraint group_members_pkey to workspace_members_pkey;
alter table workspace_members rename constraint group_members_group_id_user_id_key
    to workspace_members_workspace_id_user_id_key;
alter table workspace_members rename constraint group_members_group_id_fkey
    to workspace_members_workspace_id_fkey;
alter table workspace_members rename constraint group_members_user_id_fkey
    to workspace_members_user_id_fkey;
alter index group_members_user_id_idx rename to workspace_members_user_id_idx;

alter table vm_requests rename constraint vm_requests_group_id_fkey to vm_requests_workspace_id_fkey;
alter index vm_requests_group_id_idx rename to vm_requests_workspace_id_idx;

alter table vms rename constraint vms_group_id_fkey to vms_workspace_id_fkey;
alter index vms_group_id_idx rename to vms_workspace_id_idx;

alter table announcements rename constraint announcements_group_id_fkey
    to announcements_workspace_id_fkey;

-- The announcement scope is text, so its labels do not follow a type rename:
-- both check constraints are rebuilt around the new value.
alter table announcements drop constraint announcements_scope_check;
alter table announcements drop constraint announcements_scope_target_chk;
update announcements set scope = 'WORKSPACE' where scope = 'GROUP';
alter table announcements
    add constraint announcements_scope_check check (scope in ('ALL', 'ORG', 'WORKSPACE'));
alter table announcements add constraint announcements_scope_target_chk check (
    (scope = 'ALL'       and org_id is null     and workspace_id is null) or
    (scope = 'ORG'       and org_id is not null and workspace_id is null) or
    (scope = 'WORKSPACE' and workspace_id is not null and org_id is null)
);

-- The grantee label is an enum value, so the partial index and the check
-- constraint that name it are expected to follow the rename by themselves —
-- they hold the value's identity, not its spelling. The block below states
-- that expectation as a test rather than an assumption: if a future PostgreSQL
-- stopped carrying stored expressions across a value rename, this fails here
-- instead of leaving a grant rule that silently matches nothing.
alter type access_grantee_type rename value 'GROUP' to 'WORKSPACE';

alter table resource_access_grants rename constraint resource_access_grants_group_role_check
    to resource_access_grants_workspace_role_check;
alter index resource_access_grants_group_uidx rename to resource_access_grants_workspace_uidx;

do $$
declare
    stale_index bigint;
    stale_check bigint;
begin
    select count(*) into stale_index
      from pg_index
     where indexrelid = 'resource_access_grants_workspace_uidx'::regclass
       and coalesce(pg_get_expr(indpred, indrelid), '') not like '%WORKSPACE%';
    if stale_index > 0 then
        raise exception 'workspace-wide grant index does not select the renamed grantee type';
    end if;

    select count(*) into stale_check
      from pg_constraint
     where conname = 'resource_access_grants_workspace_role_check'
       and pg_get_constraintdef(oid) not like '%WORKSPACE%';
    if stale_check > 0 then
        raise exception 'workspace-wide grant role check does not name the renamed grantee type';
    end if;
end $$;

comment on column workspaces.deleted_at is
    'Soft-delete stamp. Deleted workspaces keep their row for resource/audit history but disappear from every list and lookup.';
comment on column workspaces.deleted_by is
    'Who deleted the workspace: an OWNER (deleteWorkspace) or the withdrawing user (PERSONAL workspace cleanup).';

comment on table resource_access_grants is
    '리소스별 접근 목록. 리소스 접근은 이 목록으로만 판정하며, 항목이 없으면 접근이 없다. 워크스페이스 소유자는 목록과 무관하게 조회·삭제·목록 관리 권한을 상시 가진다.';
comment on column resource_access_grants.resource_id is
    '리소스 식별자. 리소스 종류마다 대상 테이블이 다르므로 외래 키를 두지 않는다 — 정합성은 리소스 삭제·구성원 제거 시의 연쇄 정리가 책임진다.';
comment on column resource_access_grants.grantee_type is
    'USER는 지정된 한 사람, WORKSPACE는 소유 워크스페이스 전체. WORKSPACE 항목의 워크스페이스는 리소스의 소유 워크스페이스이므로 따로 저장하지 않는다.';
