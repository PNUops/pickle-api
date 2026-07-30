-- Index the allocation lookup that runs on every provisioning and every VM
-- detail view.
--
-- IpAllocationRepository.findFirstByVmIdAndStatusOrderByIdDesc filters on
-- (vm_id, status) and takes the newest row. The table carried no index on vm_id
-- at all: the existing ones cover (pool_id, status) and the ip uniqueness, so
-- neither can serve this. That made it a sequential scan over a table which
-- grows with every address ever handed out and keeps RELEASED rows for the
-- quarantine window, so the scan gets slower for as long as the platform runs
-- and never recovers.
--
-- Column order follows the query: equality columns first, then the sort column
-- descending, so the newest matching row is the first index entry read.
create index ip_allocations_vm_id_status_idx
    on ip_allocations (vm_id, status, id desc);

-- V62 renamed vm_templates to os_images and renamed its index, but a table
-- rename does not carry constraint names with it, so the primary key and the
-- unique key still announce a table that no longer exists. Nothing breaks -- but
-- the next person to read a constraint violation, or to look one up by name,
-- is told about a table they cannot find.
alter table os_images rename constraint vm_templates_pkey to os_images_pkey;
alter table os_images rename constraint vm_templates_name_version_key
    to os_images_name_version_key;
