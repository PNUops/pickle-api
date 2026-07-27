-- User-VM vmids move off Proxmox GET /cluster/nextid onto a DB-owned
-- monotonic sequence. nextid hands out the smallest free number, which
-- (a) dropped user VMs right next to the infra LXCs (103, 104, ...) and
-- (b) recycled destroyed guests' numbers, so Proxmox task history mixed
-- entries of unrelated VMs under one vmid (observed live: vmid 103 was
-- reused by six successive dev VMs).
-- VMID bands: 100-999 infra LXC (manual) / 1000-9999 templates
-- (sequential from 1000) / 10000-99999 reserved / 100000+ user VMs.
-- The sequence allocates; monotonic growth means a vmid is never reused.
-- maxvalue encodes Proxmox's own vmid ceiling (999999999).
-- V9's partial unique index stays as the active-row uniqueness backstop
-- (its "nextid recycles vmids" rationale is now historical).
create sequence vmid_seq start with 100000 maxvalue 999999999;

-- Point provisioning at the rebuilt template: 1000 is the first number of
-- the template band (the V3 seed pointed all three presets at the retired
-- 9000, one literal for all rows).
update vm_templates set proxmox_vmid = 1000 where proxmox_vmid = 9000;
