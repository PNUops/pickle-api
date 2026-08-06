-- SSH gateway route resolution (the internal SSH gateway route contract).
-- sshpiper asks POST /internal/sshgw/route on every incoming SSH
-- connection; the route is only returned when the VM is RUNNING, not blocked,
-- and the gateway is globally enabled. Two kill switches back that:
--
--  * per-VM  — vms.ssh_gateway_blocked: sys-admin blocks a single VM's SSH
--              access (e.g. suspected shared-password compromise) without
--              touching its power state.
--  * global  — settings.ssh_gateway_enabled: sys-admin disables the whole
--              gateway (route endpoint denies everything; a systemd stop is the
--              belt-and-suspenders on the sshgw side).
--
-- Global default is FALSE: the gateway LXC is not deployed yet, so until the
-- operator flips this on at cutover the endpoint fails closed and hands out no
-- routes even though the code is live.

alter table vms
    add column ssh_gateway_blocked boolean not null default false;

comment on column vms.ssh_gateway_blocked is
    'Per-VM SSH gateway kill switch: when true POST /internal/sshgw/route denies this VM regardless of power state. Cleared by a sys-admin to restore access.';

-- The global ssh_gateway_enabled kill switch is not seeded here. Whether the
-- gateway is wired and safe to serve is a fact about a deployment, so an operator
-- bootstrap script writes the row; the dev/test seeder supplies an equivalent.
-- Route lookups deny all traffic while the switch is absent or false.
