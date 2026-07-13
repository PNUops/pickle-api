package kr.ac.pusan.pickle.vm;

/** Kinds of entries in the permanent per-VM history (docs/plan/02 vm_events). */
public enum VmEventType {
    CREATE,
    START,
    STOP,
    REBOOT,
    FORCE_STOP,
    DELETE,
    EMERGENCY_DELETE,
    REINSTALL,
    /** Admin scheduled a routine delete (audit trail, contract v0.3.x). */
    SCHEDULE_DELETE,
    /** Admin canceled a scheduled delete before it ran. */
    CANCEL_SCHEDULED_DELETE,
    /** HTTP service publish accepted (route/domain created — M4A). */
    PUBLISH,
    /** HTTP service publish revoked (route removed — M4A). */
    UNPUBLISH,
    /** Expiry sweeper auto-stopped the VM past its end date (M5, actor null). */
    EXPIRE_STOP,
    /** Admin changed the usage period (M5 {@code PATCH /admin/vms/{vmId}/period}). */
    PERIOD_UPDATE
}
