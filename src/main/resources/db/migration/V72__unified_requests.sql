-- A request stops being a VM request and becomes a request for a resource of
-- some type.
--
-- Everything a request says about who is asking, on whose behalf, why and for
-- how long is the same question whichever kind of resource is being asked for;
-- only the specification is not. So the common part stays in one table that
-- carries the type, and each type keeps its own detail row. The alternative --
-- a table per type -- would have duplicated the state machine, the cancel
-- rules, the notification wiring and the approval queue once per type, and the
-- duplicates would have drifted.
--
-- The detail is a real table rather than a JSON column: the specification is
-- validated, constrained and joined against the OS catalog, and none of that
-- survives being moved into a document.
--
-- No row here describes the environment. Every value written is read from the
-- row it replaces, and on an empty database this file writes nothing.

alter table vm_requests rename to requests;
alter table vm_request_reviews rename to request_reviews;
alter type vm_request_status rename to request_status;

alter table requests add column resource_type resource_type;
update requests set resource_type = 'VM';
alter table requests alter column resource_type set not null;

comment on column requests.resource_type is
    '무엇을 신청하는지. 공통 항목은 이 테이블에, 종류별 항목은 <종류>_request_details에 있다.';

create table vm_request_details (
    request_id        bigint primary key references requests (id),
    image_id          bigint not null references os_images (id),
    flavor_id         bigint references vm_flavors (id),
    req_vcpu          int not null,
    req_memory_mb     int not null,
    req_disk_gb       int not null,
    spec_reason       text,
    desired_slug      text,
    -- Historical: the request form carried a domain axis until 2026-08-07.
    desired_subdomain text,
    root_domain       text,
    -- What the reviewer granted. The granted period stays on the review row
    -- because every resource type has a period; only the specification is
    -- particular to the VM.
    granted_vcpu      int,
    granted_memory_mb int,
    granted_disk_gb   int,
    granted_image_id  bigint references os_images (id),
    node_id           bigint references nodes (id)
);

insert into vm_request_details (request_id, image_id, flavor_id, req_vcpu, req_memory_mb,
                                req_disk_gb, spec_reason, desired_slug, desired_subdomain,
                                root_domain, granted_vcpu, granted_memory_mb, granted_disk_gb,
                                granted_image_id, node_id)
select r.id, r.image_id, r.flavor_id, r.req_vcpu, r.req_memory_mb,
       r.req_disk_gb, r.spec_reason, r.desired_slug, r.desired_subdomain,
       r.root_domain, rv.granted_vcpu, rv.granted_memory_mb, rv.granted_disk_gb,
       rv.granted_image_id, rv.node_id
  from requests r
  left join request_reviews rv on rv.request_id = r.id;

-- Every request kept its specification, and every decided request kept what was
-- granted. Counting rows alone would pass on a join that dropped the granted
-- side, so the second check compares against the reviews the values came from.
do $$
declare
    orphaned bigint;
    lost     bigint;
begin
    select count(*) into orphaned
      from requests r
     where not exists (select 1 from vm_request_details d where d.request_id = r.id);
    if orphaned > 0 then
        raise exception 'request detail split missed % request(s)', orphaned;
    end if;

    select count(*) into lost
      from request_reviews rv
      join vm_request_details d on d.request_id = rv.request_id
     where rv.granted_vcpu is distinct from d.granted_vcpu
        or rv.granted_memory_mb is distinct from d.granted_memory_mb
        or rv.granted_disk_gb is distinct from d.granted_disk_gb
        or rv.granted_image_id is distinct from d.granted_image_id
        or rv.node_id is distinct from d.node_id;
    if lost > 0 then
        raise exception 'granted specification differs on % review(s)', lost;
    end if;
end $$;

-- The specification constraints move with the columns they guard.
alter table requests drop constraint chk_vm_requests_positive_specs;
alter table request_reviews drop constraint chk_reviews_approve_granted;
alter table request_reviews drop constraint chk_reviews_granted_positive;

alter table requests
    drop column image_id,
    drop column flavor_id,
    drop column req_vcpu,
    drop column req_memory_mb,
    drop column req_disk_gb,
    drop column spec_reason,
    drop column desired_slug,
    drop column desired_subdomain,
    drop column root_domain;

alter table request_reviews
    drop column granted_vcpu,
    drop column granted_memory_mb,
    drop column granted_disk_gb,
    drop column granted_image_id,
    drop column node_id;

alter table vm_request_details
    add constraint chk_vm_request_details_positive_specs
    check (req_vcpu > 0 and req_memory_mb > 0 and req_disk_gb > 0);
alter table vm_request_details
    add constraint chk_vm_request_details_granted_positive
    check ((granted_vcpu is null or granted_vcpu > 0)
        and (granted_memory_mb is null or granted_memory_mb > 0)
        and (granted_disk_gb is null or granted_disk_gb > 0));
-- A granted specification is all-or-nothing. The stronger rule V11 had -- an
-- APPROVE decision must carry one -- reads across two tables now and a CHECK
-- cannot, so V74 restores it as a deferred constraint trigger.
alter table vm_request_details
    add constraint chk_vm_request_details_approved_granted
    check ((granted_vcpu is not null and granted_memory_mb is not null
            and granted_disk_gb is not null and granted_image_id is not null)
        or granted_vcpu is null);

-- Names that outlived the table they were created on.
alter table requests rename constraint vm_requests_pkey to requests_pkey;
alter table requests rename constraint chk_vm_requests_date_order to chk_requests_date_order;
alter table requests rename constraint vm_requests_workspace_id_fkey to requests_workspace_id_fkey;
alter table requests rename constraint vm_requests_org_id_fkey to requests_org_id_fkey;
alter table requests rename constraint vm_requests_requester_id_fkey to requests_requester_id_fkey;
alter index vm_requests_workspace_id_idx rename to requests_workspace_id_idx;
alter index vm_requests_org_id_status_idx rename to requests_org_id_status_idx;
alter index vm_requests_status_idx rename to requests_status_idx;
alter index vm_requests_requester_id_idx rename to requests_requester_id_idx;

alter table request_reviews rename constraint vm_request_reviews_pkey to request_reviews_pkey;
alter table request_reviews rename constraint vm_request_reviews_request_id_key
    to request_reviews_request_id_key;
alter table request_reviews rename constraint vm_request_reviews_request_id_fkey
    to request_reviews_request_id_fkey;
alter table request_reviews rename constraint vm_request_reviews_reviewer_id_fkey
    to request_reviews_reviewer_id_fkey;
alter table request_reviews rename constraint chk_reviews_granted_date_order
    to chk_request_reviews_granted_date_order;

-- The approval queue filters by type, and the requester's own list does not.
create index requests_resource_type_status_idx on requests (resource_type, status);
