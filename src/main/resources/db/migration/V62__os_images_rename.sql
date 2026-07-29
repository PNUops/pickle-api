-- Rename the OS catalog to match what it holds. V58 moved the spec presets out
-- into vm_flavors, leaving vm_templates a pure OS catalog under a name that no
-- longer describes it; the foreign keys pointing at it follow the same rename.
--
-- Storage-side only. The /templates and /admin/templates paths, the request and
-- response field names (templateId, grantedTemplateId) and the template_status
-- enum type (shared with vm_flavors) all stay as they are.

alter table vm_templates rename to os_images;
alter index vm_templates_node_id_idx rename to os_images_node_id_idx;

alter table vm_requests rename column template_id to image_id;
alter table vms rename column template_id to image_id;
alter table vm_request_reviews rename column granted_template_id to granted_image_id;
