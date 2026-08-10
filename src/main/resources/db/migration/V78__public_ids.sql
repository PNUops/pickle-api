-- Public identifiers: every id that crosses the API boundary becomes a UUID.
--
-- Sequential ids in URLs disclose the platform's size and its growth rate.
-- Measured before this migration: vms 63 rows / max id 63, requests 72/72,
-- domains 75/75, routes 75/75 -- the number in the URL *is* the count. Others
-- leak cumulative creation including deletions (workspaces 236/286, users
-- 149/207, notifications 1,695/1,769).
--
-- The primary keys stay bigint. Only the *public* face changes. Replacing the
-- keys would take 62 foreign keys, 27 id-ordered sorts, the relay's nftables
-- object names and the capacity-trend SQL with it, and a random primary key
-- costs index locality on every insert; a secondary unique index on tables of
-- this size costs nothing. So each exposed table gains one column.
--
-- Which tables: derived from the generated contract, not from intuition -- a
-- table is here iff some id referring to it appears in a path, a query
-- parameter, a request body or a response field. That test excludes several
-- tables an earlier survey had listed: workspace_members, request_reviews,
-- user_status_changes, vm_settings, vm_request_details and terms_versions all
-- expose only *foreign* keys or a natural key, never their own row id.
--
-- The column carries a database default even though the application generates
-- the value in Java. Six of these tables are also written by raw SQL that never
-- touches JPA -- the development seeder and the operator's inventory script --
-- and a NOT NULL column without a default would break both. The default is a
-- safety net, not the intended path.

alter table users add column public_id uuid not null default gen_random_uuid();
alter table orgs add column public_id uuid not null default gen_random_uuid();
alter table workspaces add column public_id uuid not null default gen_random_uuid();
alter table vms add column public_id uuid not null default gen_random_uuid();
alter table requests add column public_id uuid not null default gen_random_uuid();
alter table campus_ip_requests add column public_id uuid not null default gen_random_uuid();
alter table domains add column public_id uuid not null default gen_random_uuid();
alter table routes add column public_id uuid not null default gen_random_uuid();
alter table certificates add column public_id uuid not null default gen_random_uuid();
alter table nodes add column public_id uuid not null default gen_random_uuid();
alter table os_images add column public_id uuid not null default gen_random_uuid();
alter table vm_flavors add column public_id uuid not null default gen_random_uuid();
alter table notifications add column public_id uuid not null default gen_random_uuid();
alter table announcements add column public_id uuid not null default gen_random_uuid();
alter table user_ssh_keys add column public_id uuid not null default gen_random_uuid();
alter table resource_access_grants add column public_id uuid not null default gen_random_uuid();
alter table port_mappings add column public_id uuid not null default gen_random_uuid();
alter table relays add column public_id uuid not null default gen_random_uuid();
alter table ip_pools add column public_id uuid not null default gen_random_uuid();
alter table ip_allocations add column public_id uuid not null default gen_random_uuid();
alter table provisioning_tasks add column public_id uuid not null default gen_random_uuid();
alter table drift_findings add column public_id uuid not null default gen_random_uuid();
alter table vm_events add column public_id uuid not null default gen_random_uuid();

create unique index users_public_id_uidx on users (public_id);
create unique index orgs_public_id_uidx on orgs (public_id);
create unique index workspaces_public_id_uidx on workspaces (public_id);
create unique index vms_public_id_uidx on vms (public_id);
create unique index requests_public_id_uidx on requests (public_id);
create unique index campus_ip_requests_public_id_uidx on campus_ip_requests (public_id);
create unique index domains_public_id_uidx on domains (public_id);
create unique index routes_public_id_uidx on routes (public_id);
create unique index certificates_public_id_uidx on certificates (public_id);
create unique index nodes_public_id_uidx on nodes (public_id);
create unique index os_images_public_id_uidx on os_images (public_id);
create unique index vm_flavors_public_id_uidx on vm_flavors (public_id);
create unique index notifications_public_id_uidx on notifications (public_id);
create unique index announcements_public_id_uidx on announcements (public_id);
create unique index user_ssh_keys_public_id_uidx on user_ssh_keys (public_id);
create unique index resource_access_grants_public_id_uidx on resource_access_grants (public_id);
create unique index port_mappings_public_id_uidx on port_mappings (public_id);
create unique index relays_public_id_uidx on relays (public_id);
create unique index ip_pools_public_id_uidx on ip_pools (public_id);
create unique index ip_allocations_public_id_uidx on ip_allocations (public_id);
create unique index provisioning_tasks_public_id_uidx on provisioning_tasks (public_id);
create unique index drift_findings_public_id_uidx on drift_findings (public_id);
create unique index vm_events_public_id_uidx on vm_events (public_id);

-- The audit trail records what a public id pointed at.
--
-- target_id is polymorphic over 15 target_type values and has no foreign key,
-- so it cannot be migrated by joining: two of those values (group, vm_request)
-- name tables that no longer exist. Existing rows therefore keep their numbers
-- as text and mean what they always meant; new rows record the public id.
--
-- The alternative was to leave the column numeric. That would have kept the
-- trail internally consistent and made the admin filter useless in the same
-- stroke: once every id on screen is a UUID, nobody can see the number to type
-- into it. The filter itself needs no change either way -- the query already
-- compares target_id::text and the controller already takes a String.
alter table audit_logs alter column target_id type text using target_id::text;

comment on column audit_logs.target_id is
    '감사 대상의 공개 식별자(UUID 문자열). V78 이전 행은 당시의 내부 숫자를 문자열로 담고 있다 — target_type이 15종이고 외래 키가 없어 조인으로 옮길 수 없으며, 그중 둘은 이미 없는 테이블을 가리킨다.';

-- The organisation slug goes with them: it existed to give an organisation a
-- stable name in a URL, which the public id now does. Nobody typed it.
alter table orgs drop column slug;

-- Corrections owed by V76, collected in the migration convention and paid here
-- because this file touches nodes. The applied file cannot be edited: Flyway
-- checksums it, and editing one after deployment took the api down for twelve
-- minutes on 2026-08-10.
comment on column nodes.disk_capacity_gb is
    '게스트 디스크가 놓이는 thin pool의 물리 용량(GiB). 운영자가 호스트에서 측정해 채우며, 측정 전에는 null이다. 오버프로비저닝을 전제하므로 배치 제한이 아니라 조언용 분모로만 쓴다. V76의 코멘트는 단위를 GB라 적었으나 저장값은 처음부터 GiB였고(측정 바이트를 1073741824로 나눈다), 값을 채우는 주체도 사설 레포의 스크립트 이름이 아니라 운영자로 적는 것이 옳다.';
