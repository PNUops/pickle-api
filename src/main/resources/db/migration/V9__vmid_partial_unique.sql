-- V9: proxmox_vmid uniqueness must not survive destruction.
--
-- V4 declared vms.proxmox_vmid globally unique, but markDeleted keeps the vmid
-- on the DELETED row (audit trail; the drift reconciler also relies on vmid
-- values of historical rows). Proxmox's /cluster/nextid freely re-issues the
-- vmid of a destroyed guest, so the next provisioning that drew a recycled
-- vmid would hit the unique constraint forever (assignProxmoxVmid treats the
-- violation as a transient race and retries) — stalling all provisioning.
--
-- Fix: uniqueness among non-DELETED rows only. DELETED rows keep their vmid
-- value untouched.
alter table vms drop constraint vms_proxmox_vmid_key;

create unique index vms_proxmox_vmid_active_uq
    on vms (proxmox_vmid)
    where status <> 'DELETED';
