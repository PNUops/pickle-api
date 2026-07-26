-- Defense-in-depth domain CHECK constraints. The service
-- layer already enforces these invariants; the constraints guarantee no code
-- path (or manual/JDBC write) can persist a row that contradicts them.
-- Each constraint mirrors an invariant that already holds in the code:
--   * users.org_id ⇔ ORG_ADMIN — AdminService.updateUser sets org_id only for
--     ORG_ADMIN and forces it null for every other role (single writer).
--   * vm_requests/vms specs are `int not null` primitives, always positive;
--     date columns are nullable, so ordering is only checked when both present.
--   * vm_request_reviews: ApprovalService.approve always writes granted_vcpu/
--     memory_mb/disk_gb/template_id (all @NotNull on the form) on APPROVE;
--     granted dates stay optional (기간 미지정 허용), REJECT leaves grants null.

-- users: org_id is set iff the role is ORG_ADMIN.
alter table users
    add constraint chk_users_org_role
    check ((role = 'ORG_ADMIN') = (org_id is not null));

-- vm_requests: requested specs are positive; end date not before start date.
alter table vm_requests
    add constraint chk_vm_requests_positive_specs
    check (req_vcpu > 0 and req_memory_mb > 0 and req_disk_gb > 0);
alter table vm_requests
    add constraint chk_vm_requests_date_order
    check (req_start_date is null or req_end_date is null
        or req_end_date >= req_start_date);

-- vms: provisioned specs are positive; end date not before start date.
alter table vms
    add constraint chk_vms_positive_specs
    check (vcpu > 0 and memory_mb > 0 and disk_gb > 0);
alter table vms
    add constraint chk_vms_date_order
    check (start_date is null or end_date is null or end_date >= start_date);

-- vm_request_reviews: an APPROVE row carries the mandatory granted spec; any
-- present granted spec is positive; granted dates (optional) stay ordered.
alter table vm_request_reviews
    add constraint chk_reviews_approve_granted
    check (decision <> 'APPROVE' or (
        granted_vcpu is not null and granted_memory_mb is not null
        and granted_disk_gb is not null and granted_template_id is not null));
alter table vm_request_reviews
    add constraint chk_reviews_granted_positive
    check ((granted_vcpu is null or granted_vcpu > 0)
        and (granted_memory_mb is null or granted_memory_mb > 0)
        and (granted_disk_gb is null or granted_disk_gb > 0));
alter table vm_request_reviews
    add constraint chk_reviews_granted_date_order
    check (granted_start_date is null or granted_end_date is null
        or granted_end_date >= granted_start_date);
