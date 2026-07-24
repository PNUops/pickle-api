-- Serialize duplicate power actions (start/shutdown/force-stop): the request
-- transaction claims a single-writer slot with an atomic CAS
-- (pending_power_action IS NULL AND status = <allowed>) before enqueuing the
-- JobRunr worker, so rapid duplicates get exactly one 202 and one 409 instead
-- of both racing to Proxmox. The worker clears the claim on every exit path;
-- StaleTaskRecoveryJob frees claims a crashed worker never released, and the
-- status poller skips VMs with a live claim so it never fights an in-flight op.
-- Contract v0.3.2 stays frozen: no new vm_status values, no endpoint changes.
-- Power operations.
alter table vms
    add column pending_power_action    text,
    add column pending_power_action_at timestamptz;

comment on column vms.pending_power_action is
    'Non-null while a start/shutdown/force-stop job is in flight (claim serialization). Holds the PowerAction name for observability; cleared by the worker on any exit path, or by StaleTaskRecoveryJob if the worker crashed. Reboot uses the RUNNING→REBOOTING transition instead and never sets this, so a force-stop can still interrupt a hung reboot.';
