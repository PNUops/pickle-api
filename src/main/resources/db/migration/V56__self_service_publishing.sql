-- Self-service HTTP publishing (2026-07-28 operator decision): the request
-- form stops asking for publish options, approval stops confirming names, and
-- the subdomain is validated/finalized at publish time instead.
--   * need_ssh / need_public never had an enforcement path; need_http gated a
--     single check (requireHttpGranted) that is removed with this change.
--   * custom_domain was informational only — custom domains are attached
--     self-service after creation.
--   * granted_subdomain / granted_root_domain move out of the review: the
--     requester's desired_subdomain / root_domain (kept on vm_requests) become
--     the publish-time default instead.
--   * display_name: the requester can now pick the VM display name up front;
--     approval seeds the vm_settings display_name row from it.

alter table vm_requests
    drop column need_ssh,
    drop column need_http,
    drop column need_public,
    drop column custom_domain,
    add column display_name text;

alter table vm_request_reviews
    drop column grant_ssh,
    drop column grant_http,
    drop column grant_public,
    drop column granted_subdomain,
    drop column granted_root_domain;
