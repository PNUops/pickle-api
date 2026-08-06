-- Three guards the schema should have carried, found in the pre-launch review.

-- A node with zero threads does not fail placement, it wins it. The scorer
-- divides allocated vCPU by cpu_threads, so zero yields NaN, and NaN compares
-- greater than every real score under the comparator that picks the best node:
-- the unusable node is chosen every time, silently. Memory is a hard admission
-- filter, so zero there refuses everything instead -- loud, but still wrong.
-- Neither column has an application writer (the entity exposes no setter), so
-- the value arrives from an operations script or by hand, which is exactly the
-- path that needs a floor.
alter table nodes
    add constraint chk_nodes_cpu_threads_positive check (cpu_threads > 0),
    add constraint chk_nodes_memory_mb_positive check (memory_mb > 0);

-- The platform wildcard is looked up with "first row matching kind and scope",
-- so two rows for one scope make the choice arbitrary: publishing could read one
-- expiry while the admin certificate list warns on the other. Nothing enforced
-- that there is only one.
--
-- Scoped narrowly on purpose. Per-domain certificates (domain_id set) legitimately
-- accumulate: unpublishing revokes the row and re-publishing inserts another for
-- the same name, so a blanket unique on (kind, scope) would refuse the second
-- publish. Revoked wildcards are excluded for the same reason -- rotation leaves
-- the old row behind.
create unique index certificates_platform_wildcard_uq
    on certificates (scope)
 where kind = 'ORIGIN_CA_WILDCARD' and domain_id is null and status <> 'REVOKED';

-- V62 renamed the table and V65 renamed its primary and unique keys, but a table
-- rename carries no constraint names with it and those two were not the only ones.
-- Nothing breaks; the cost is that a constraint violation, or anyone looking one
-- up by name, still names a table that no longer exists.
alter table os_images rename constraint vm_templates_node_id_fkey to os_images_node_id_fkey;
alter table os_images rename constraint vm_templates_id_not_null to os_images_id_not_null;
alter table os_images rename constraint vm_templates_name_not_null to os_images_name_not_null;
alter table os_images rename constraint vm_templates_display_name_not_null to os_images_display_name_not_null;
alter table os_images rename constraint vm_templates_proxmox_vmid_not_null to os_images_proxmox_vmid_not_null;
alter table os_images rename constraint vm_templates_node_id_not_null to os_images_node_id_not_null;
alter table os_images rename constraint vm_templates_version_not_null to os_images_version_not_null;
alter table os_images rename constraint vm_templates_min_disk_gb_not_null to os_images_min_disk_gb_not_null;
alter table os_images rename constraint vm_templates_status_not_null to os_images_status_not_null;
alter table os_images rename constraint vm_templates_created_at_not_null to os_images_created_at_not_null;
alter table os_images rename constraint vm_templates_updated_at_not_null to os_images_updated_at_not_null;
