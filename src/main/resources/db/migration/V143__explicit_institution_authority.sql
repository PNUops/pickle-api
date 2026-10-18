-- Explicit authority cutover. Only existing memberships are transformed.
-- No identity, status, recipient snapshot, notification, or environment row is inserted.
lock table users, user_org_roles in share row exclusive mode;

create function institution_permission_valid(row_mode text, payload jsonb, display_role text)
returns boolean language plpgsql immutable strict as $validation$
declare
    work_ids text[];
    selected_groups text[];
    stored_groups text[];
    scope_entry record;
    scope_keys text[];
    workspace_count integer;
    distinct_workspace_count integer;
begin
    if jsonb_typeof(payload) <> 'object' then return false; end if;
    if (select array_agg(key order by key) from jsonb_object_keys(payload) as fields(key))
            is distinct from array['scopes','work']::text[] then return false; end if;
    if jsonb_typeof(payload->'work') <> 'array' or jsonb_typeof(payload->'scopes') <> 'object' then return false; end if;
    if exists(select 1 from jsonb_array_elements(payload->'work') value where jsonb_typeof(value) <> 'string') then return false; end if;
    select coalesce(array_agg(value order by value),array[]::text[]) into work_ids from jsonb_array_elements_text(payload->'work') value;
    if cardinality(work_ids) <> (select count(distinct value) from unnest(work_ids) value) then return false; end if;
    if row_mode='FULL' then
        return cardinality(work_ids)=0 and payload->'scopes'='{}'::jsonb and display_role='ORG_ADMIN';
    end if;
    if row_mode<>'SELECTED' or cardinality(work_ids)=0 or 'institution.delegate'=any(work_ids) then return false; end if;
    if not work_ids <@ array['vm.request.read','vm.request.decide','vm.roster.issue','vm.recipient.retry','vm.metadata.read','vm.observe','vm.power','vm.period','vm.deletion.schedule','vm.network-policy.read','vm.network-policy.write','vm.publication.read','vm.publication.operate','vm.custom-domain.verify','vm.terminal-session.read','vm.request.support.read','vm.metadata.support.read','vm.acl.support.read','vm.acl.support.manage','llm.request.read','llm.request.decide','llm.roster.issue','llm.recipient.retry','llm.metadata.read','llm.observe','llm.token.operate','llm.state.operate','llm.period','llm.revoke','llm.key.cost.read','llm.key.budget','llm.request.support.read','llm.metadata.support.read','llm.observe.support.read','llm.acl.support.read','llm.acl.support.manage','domain.request.read','domain.request.decide','domain.metadata.read','domain.observe','domain.renewal','domain.release','domain.request.support.read','domain.metadata.support.read','domain.acl.support.read','domain.acl.support.manage','gpu.request.read','gpu.request.decide','gpu.metadata.read','gpu.observe','gpu.period','gpu.recall','gpu.request.support.read','gpu.metadata.support.read','gpu.observe.support.read','gpu.acl.support.read','gpu.acl.support.manage','workspace.support.read','workspace.info.update','workspace.members.manage','workspace.invitations.manage','workspace.owners.manage','workspace.terminate','workspace.recipient.cancel','workspace.announcement.send','student.lookup','student.support.read','student.profile.correct','notice.read','notice.manage','shared-certificate.read','root-domain.catalogue.read','llm.model-catalogue.read','institution.audit.read','institution.staff.read','institution.request-mail.read','institution.request-mail.configure','institution.announcement.read','institution.announcement.send','institution.account.read','institution.account.finance.read','institution.account.manage','institution.account.credential.manage','institution.root-policy.read','institution.root-policy.write','institution.delegate']::text[] then return false; end if;
    if display_role is distinct from (case when work_ids && array['vm.request.decide','vm.roster.issue','vm.recipient.retry','vm.power','vm.period','vm.deletion.schedule','vm.network-policy.write','vm.publication.operate','vm.custom-domain.verify','vm.acl.support.manage','llm.request.decide','llm.roster.issue','llm.recipient.retry','llm.token.operate','llm.state.operate','llm.period','llm.revoke','llm.key.budget','llm.acl.support.manage','domain.request.decide','domain.renewal','domain.release','domain.acl.support.manage','gpu.request.decide','gpu.period','gpu.recall','gpu.acl.support.manage','workspace.info.update','workspace.members.manage','workspace.invitations.manage','workspace.owners.manage','workspace.terminate','workspace.recipient.cancel','workspace.announcement.send','student.profile.correct','notice.manage','institution.request-mail.configure','institution.announcement.send','institution.account.manage','institution.account.credential.manage','institution.root-policy.write','institution.delegate']::text[] then 'ORG_MANAGER' else 'ORG_VIEWER' end) then return false; end if;
    select coalesce(array_agg(distinct group_id order by group_id),array[]::text[]) into selected_groups
      from (select case item when 'vm.request.read' then 'VM' when 'vm.request.decide' then 'VM' when 'vm.roster.issue' then 'VM' when 'vm.recipient.retry' then 'VM' when 'vm.metadata.read' then 'VM' when 'vm.observe' then 'VM' when 'vm.power' then 'VM' when 'vm.period' then 'VM' when 'vm.deletion.schedule' then 'VM' when 'vm.network-policy.read' then 'VM' when 'vm.network-policy.write' then 'VM' when 'vm.publication.read' then 'VM' when 'vm.publication.operate' then 'VM' when 'vm.custom-domain.verify' then 'VM' when 'vm.terminal-session.read' then 'VM' when 'vm.request.support.read' then 'VM' when 'vm.metadata.support.read' then 'VM' when 'vm.acl.support.read' then 'VM' when 'vm.acl.support.manage' then 'VM' when 'llm.request.read' then 'LLM' when 'llm.request.decide' then 'LLM' when 'llm.roster.issue' then 'LLM' when 'llm.recipient.retry' then 'LLM' when 'llm.metadata.read' then 'LLM' when 'llm.observe' then 'LLM' when 'llm.token.operate' then 'LLM' when 'llm.state.operate' then 'LLM' when 'llm.period' then 'LLM' when 'llm.revoke' then 'LLM' when 'llm.key.cost.read' then 'LLM' when 'llm.key.budget' then 'LLM' when 'llm.request.support.read' then 'LLM' when 'llm.metadata.support.read' then 'LLM' when 'llm.observe.support.read' then 'LLM' when 'llm.acl.support.read' then 'LLM' when 'llm.acl.support.manage' then 'LLM' when 'domain.request.read' then 'DOMAIN' when 'domain.request.decide' then 'DOMAIN' when 'domain.metadata.read' then 'DOMAIN' when 'domain.observe' then 'DOMAIN' when 'domain.renewal' then 'DOMAIN' when 'domain.release' then 'DOMAIN' when 'domain.request.support.read' then 'DOMAIN' when 'domain.metadata.support.read' then 'DOMAIN' when 'domain.acl.support.read' then 'DOMAIN' when 'domain.acl.support.manage' then 'DOMAIN' when 'gpu.request.read' then 'GPU' when 'gpu.request.decide' then 'GPU' when 'gpu.metadata.read' then 'GPU' when 'gpu.observe' then 'GPU' when 'gpu.period' then 'GPU' when 'gpu.recall' then 'GPU' when 'gpu.request.support.read' then 'GPU' when 'gpu.metadata.support.read' then 'GPU' when 'gpu.observe.support.read' then 'GPU' when 'gpu.acl.support.read' then 'GPU' when 'gpu.acl.support.manage' then 'GPU' when 'workspace.support.read' then 'WORKSPACE' when 'workspace.info.update' then 'WORKSPACE' when 'workspace.members.manage' then 'WORKSPACE' when 'workspace.invitations.manage' then 'WORKSPACE' when 'workspace.owners.manage' then 'WORKSPACE' when 'workspace.terminate' then 'WORKSPACE' when 'workspace.recipient.cancel' then 'WORKSPACE' when 'workspace.announcement.send' then 'WORKSPACE' else null end as group_id from unnest(work_ids) item) groups where group_id is not null;
    select coalesce(array_agg(key order by key),array[]::text[]) into stored_groups from jsonb_object_keys(payload->'scopes') as groups(key);
    if selected_groups is distinct from stored_groups then return false; end if;
    for scope_entry in select key,value from jsonb_each(payload->'scopes') loop
        if jsonb_typeof(scope_entry.value)<>'object' then return false; end if;
        select array_agg(key order by key) into scope_keys from jsonb_object_keys(scope_entry.value) as fields(key);
        if scope_keys is distinct from array['mode','workspaceIds']::text[] then return false; end if;
        if jsonb_typeof(scope_entry.value->'mode')<>'string'
                or (scope_entry.value->>'mode') not in ('ALL','LISTED')
                or jsonb_typeof(scope_entry.value->'workspaceIds')<>'array' then return false; end if;
        if exists(select 1 from jsonb_array_elements(scope_entry.value->'workspaceIds') value
                where jsonb_typeof(value)<>'string' or (value #>> '{}') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') then return false; end if;
        select count(*),count(distinct value) into workspace_count,distinct_workspace_count
          from jsonb_array_elements_text(scope_entry.value->'workspaceIds') value;
        if workspace_count<>distinct_workspace_count then return false; end if;
        if scope_entry.value->>'mode'='ALL' and workspace_count<>0 then return false; end if;
    end loop;
    return true;
end
$validation$;

-- The old and explicit states are checked before any row is changed.
do $preflight$
begin
    if exists(select 1 from user_org_roles where num_nonnulls(permission_mode,permission_payload,permission_revision) not in (0,3)
            or permission_mode is not null and (permission_revision<=0 or not institution_permission_valid(permission_mode,permission_payload,role::text))) then
        raise exception 'Institution permission cutover: invalid explicit membership state';
    end if;
    if exists(select 1 from users u where
        (u.role::text in ('ORG_ADMIN','ORG_MANAGER','ORG_VIEWER') and u.role is distinct from
            (select r.role from user_org_roles r where r.user_id=u.id
               order by case r.role when 'ORG_ADMIN' then 3 when 'ORG_MANAGER' then 2 else 1 end desc limit 1))
        or (u.role::text not in ('ORG_ADMIN','ORG_MANAGER','ORG_VIEWER') and exists(select 1 from user_org_roles r where r.user_id=u.id))) then
        raise exception 'Institution permission cutover: account role and membership sources disagree';
    end if;
end
$preflight$;

-- Preserve issued values and consumed gaps before assigning fresh source revisions.
select setval('institution_permission_revision_seq',
    greatest((select last_value from institution_permission_revision_seq),
        coalesce((select max(permission_revision) from user_org_roles),1)),
    (select is_called from institution_permission_revision_seq)
        or exists(select 1 from user_org_roles where permission_revision is not null));

-- The current roster epoch advances; saved enqueue-time policies remain immutable.
update orgs o set admin_revision=admin_revision+1
 where exists(select 1 from user_org_roles r where r.org_id=o.id and r.permission_mode is null);

-- These two finite lists are the reviewed legacy operations, never an enum flag filter.
-- Existing inactive memberships are transformed identically and retain their status.
update user_org_roles set
    permission_mode=case role::text when 'ORG_ADMIN' then 'FULL' else 'SELECTED' end,
    permission_payload=case role::text
        when 'ORG_ADMIN' then '{"work":[],"scopes":{}}'::jsonb
        when 'ORG_MANAGER' then '{"work":["domain.acl.support.manage","domain.acl.support.read","domain.metadata.read","domain.metadata.support.read","domain.observe","domain.release","domain.renewal","domain.request.decide","domain.request.read","domain.request.support.read","gpu.acl.support.manage","gpu.acl.support.read","gpu.metadata.read","gpu.metadata.support.read","gpu.observe","gpu.observe.support.read","gpu.request.decide","gpu.request.read","gpu.request.support.read","institution.account.credential.manage","institution.account.finance.read","institution.account.manage","institution.account.read","institution.announcement.read","institution.audit.read","institution.request-mail.read","institution.root-policy.read","institution.root-policy.write","institution.staff.read","llm.acl.support.manage","llm.acl.support.read","llm.key.budget","llm.key.cost.read","llm.metadata.read","llm.metadata.support.read","llm.model-catalogue.read","llm.observe","llm.observe.support.read","llm.period","llm.recipient.retry","llm.request.decide","llm.request.read","llm.request.support.read","llm.roster.issue","llm.state.operate","llm.token.operate","notice.read","root-domain.catalogue.read","shared-certificate.read","student.lookup","student.profile.correct","student.support.read","vm.acl.support.manage","vm.acl.support.read","vm.custom-domain.verify","vm.metadata.read","vm.metadata.support.read","vm.network-policy.read","vm.network-policy.write","vm.observe","vm.period","vm.power","vm.publication.operate","vm.publication.read","vm.recipient.retry","vm.request.decide","vm.request.read","vm.request.support.read","vm.roster.issue","vm.terminal-session.read","workspace.info.update","workspace.invitations.manage","workspace.members.manage","workspace.owners.manage","workspace.recipient.cancel","workspace.support.read","workspace.terminate"],"scopes":{"VM":{"mode":"ALL","workspaceIds":[]},"LLM":{"mode":"ALL","workspaceIds":[]},"DOMAIN":{"mode":"ALL","workspaceIds":[]},"GPU":{"mode":"ALL","workspaceIds":[]},"WORKSPACE":{"mode":"ALL","workspaceIds":[]}}}'::jsonb
        when 'ORG_VIEWER' then '{"work":["domain.acl.support.read","domain.metadata.read","domain.metadata.support.read","domain.observe","domain.request.read","domain.request.support.read","gpu.acl.support.read","gpu.metadata.read","gpu.metadata.support.read","gpu.observe","gpu.observe.support.read","gpu.request.read","gpu.request.support.read","institution.account.finance.read","institution.account.read","institution.announcement.read","institution.request-mail.read","institution.root-policy.read","institution.staff.read","llm.acl.support.read","llm.key.cost.read","llm.metadata.read","llm.metadata.support.read","llm.model-catalogue.read","llm.observe","llm.observe.support.read","llm.request.read","llm.request.support.read","notice.read","root-domain.catalogue.read","shared-certificate.read","student.lookup","student.support.read","vm.acl.support.read","vm.metadata.read","vm.metadata.support.read","vm.network-policy.read","vm.observe","vm.publication.read","vm.request.read","vm.request.support.read","vm.terminal-session.read","workspace.support.read"],"scopes":{"VM":{"mode":"ALL","workspaceIds":[]},"LLM":{"mode":"ALL","workspaceIds":[]},"DOMAIN":{"mode":"ALL","workspaceIds":[]},"GPU":{"mode":"ALL","workspaceIds":[]},"WORKSPACE":{"mode":"ALL","workspaceIds":[]}}}'::jsonb end,
    permission_revision=nextval('institution_permission_revision_seq')
 where permission_mode is null and permission_payload is null and permission_revision is null;

alter table user_org_roles
    alter column permission_mode set not null,
    alter column permission_payload set not null,
    alter column permission_revision set not null,
    add constraint chk_user_org_roles_permission_content
        check(institution_permission_valid(permission_mode,permission_payload,role::text));

comment on table user_org_roles is
    '기관별 명시 업무, 그룹 범위와 독립 원천 버전. role은 부여 값에서 계산하는 표시이며 업무 인가의 대체 값이 아님';
comment on column user_org_roles.permission_mode is
    '명시 기관 권한 FULL 또는 SELECTED. 미전환 값을 자동으로 복구하거나 로그인 때 이관하지 않음';
comment on column user_org_roles.request_mail is
    '기관 신청 메일의 지정 선택. 실제 신청 종류·워크스페이스 결정 업무와 계정 상태를 큐 등록 때 평가하며 저장한 수신자·주소·본문은 나중에 재선정하지 않음';
