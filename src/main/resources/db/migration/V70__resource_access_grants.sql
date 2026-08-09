-- Access to a resource stops being implied by a role in its owning group and
-- becomes an explicit list attached to the resource itself.
--
-- The table is keyed by resource TYPE plus id rather than by vm_id: containers
-- and LLM API keys are decided additions that pose the same question (one
-- object, its own set of people), and each should arrive as a new enum value
-- and an adapter rather than as another table with the same five columns.
--
-- The rows written below seed nothing. They re-express state this database
-- already holds — every grant is derived from a membership that exists here —
-- so the file states no fact about the environment it runs on, and on a fresh
-- database it correctly writes nothing at all. It transforms with the schema
-- rather than after it because the new judgment reads grants: any gap between
-- the two would be a gap in which nobody can reach their own VM.

create type resource_type as enum ('VM');

create type access_grantee_type as enum ('USER', 'GROUP');

-- The resource axis has its own rungs. It reads the same for every resource
-- type (see it / use it / configure it / decide who reaches it), while what
-- each rung concretely permits is per-type.
create type resource_role as enum ('OWNER', 'EDITOR', 'MEMBER', 'VIEWER');

create table resource_access_grants (
    id            bigint generated always as identity primary key,
    resource_type resource_type not null,
    resource_id   bigint not null,
    grantee_type  access_grantee_type not null,
    user_id       bigint references users (id),
    role          resource_role not null,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    -- A user grant names a user; a group-wide grant never does.
    constraint resource_access_grants_grantee_check
        check ((grantee_type = 'USER') = (user_id is not null)),
    -- A group-wide grant may not hand the whole group the rungs that manage
    -- access or destroy the resource.
    constraint resource_access_grants_group_role_check
        check (grantee_type <> 'GROUP' or role in ('MEMBER', 'VIEWER'))
);

-- One grant per person per resource, and at most one group-wide grant.
create unique index resource_access_grants_user_uidx
    on resource_access_grants (resource_type, resource_id, user_id)
    where grantee_type = 'USER';
create unique index resource_access_grants_group_uidx
    on resource_access_grants (resource_type, resource_id)
    where grantee_type = 'GROUP';
-- Answers "which resources may this person reach" for the list surfaces.
create index resource_access_grants_user_id_idx on resource_access_grants (user_id);

comment on table resource_access_grants is
    '자원별 접근 목록. 자원 접근은 이 목록으로만 판정하며, 항목이 없으면 접근이 없다. 그룹 소유자는 목록과 무관하게 조회·삭제·목록 관리 권한을 상시 가진다.';
comment on column resource_access_grants.resource_id is
    '자원 식별자. 자원 종류마다 대상 테이블이 다르므로 외래 키를 두지 않는다 — 정합성은 자원 삭제·구성원 제거 시의 연쇄 정리가 책임진다.';
comment on column resource_access_grants.grantee_type is
    'USER는 지정된 한 사람, GROUP은 소유 그룹 전체. GROUP 항목의 그룹은 자원의 소유 그룹이므로 따로 저장하지 않는다.';

-- Existing VMs: every current member becomes an individual grant at the rung
-- they already held, so nobody's ability to reach a VM changes on the way
-- across. Deleted VMs are included deliberately — their detail and history
-- stay visible to the same people as before.
insert into resource_access_grants (resource_type, resource_id, grantee_type, user_id, role)
select 'VM', v.id, 'USER', gm.user_id, gm.role::text::resource_role
  from vms v
  join group_members gm on gm.group_id = v.group_id;

-- Checked here, before the group ladder is flattened: afterwards the rung each
-- grant was derived from no longer exists, so this is the only moment the two
-- sides can be compared. Counting rows would not catch the mistake that matters
-- — a wrong rung on every row keeps the count exactly right — so this compares
-- each grant against the membership it came from.
do $$
declare
    mismatched bigint;
    missing    bigint;
begin
    select count(*) into mismatched
      from vms v
      join group_members gm on gm.group_id = v.group_id
      join resource_access_grants g
        on g.resource_type = 'VM' and g.resource_id = v.id and g.user_id = gm.user_id
     where g.role::text <> gm.role::text;
    if mismatched > 0 then
        raise exception 'access grant rung mismatch on % row(s)', mismatched;
    end if;

    select count(*) into missing
      from vms v
      join group_members gm on gm.group_id = v.group_id
     where not exists (select 1 from resource_access_grants g
                        where g.resource_type = 'VM' and g.resource_id = v.id
                          and g.user_id = gm.user_id);
    if missing > 0 then
        raise exception 'access grant materialization missed % membership(s)', missing;
    end if;
end $$;

-- The group ladder keeps only the two rungs that describe standing in a group.
-- Its middle rungs were about VMs, and VMs are now the other axis's business.
-- Requesting a VM moves with it: it becomes something any member may do, since
-- the approval step is what actually holds the line.
update group_members
   set role = 'MEMBER'
 where role in ('EDITOR', 'VIEWER');

alter type group_member_role rename to group_member_role_legacy;
create type group_member_role as enum ('OWNER', 'MEMBER');
alter table group_members
    alter column role type group_member_role using role::text::group_member_role;
drop type group_member_role_legacy;

-- No group row is left on a rung the type no longer has. (The cast above would
-- already have failed; this states the intent so a later edit cannot quietly
-- drop it.)
do $$
declare
    stragglers bigint;
begin
    select count(*) into stragglers
      from group_members where role not in ('OWNER', 'MEMBER');
    if stragglers > 0 then
        raise exception 'group ladder flatten missed % row(s)', stragglers;
    end if;
end $$;
