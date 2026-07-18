package kr.ac.pusan.pickle.notification;

/**
 * The notification event catalog (contract v0.5.0 {@code NotificationView.event}).
 * Each event carries its dot-namespaced id, default importance, and whether the
 * email channel is on ({@code false} → rows are inserted {@code SKIPPED} and
 * never mailed). {@code VM_EXPIRY_NOTICE} renders a per-stage id
 * ({@code vm.expiry.d7} …) in the composer.
 *
 * <p>The two expiry events are published by the M5 expiry job (api-B lane);
 * they are defined here so the catalog is complete from the start.</p>
 */
public enum NotificationEvent {

    REQUEST_SUBMITTED("request.submitted", NotificationImportance.NORMAL),
    REQUEST_APPROVED("request.approved", NotificationImportance.NORMAL),
    REQUEST_REJECTED("request.rejected", NotificationImportance.NORMAL),
    VM_CREATE_DONE("vm.create.done", NotificationImportance.NORMAL),
    VM_CREATE_FAILED("vm.create.failed", NotificationImportance.HIGH),
    VM_DELETE_ACCEPTED("vm.delete.accepted", NotificationImportance.NORMAL),
    VM_DELETE_SCHEDULED("vm.delete.scheduled", NotificationImportance.HIGH),
    VM_DELETE_CANCELED("vm.delete.canceled", NotificationImportance.NORMAL),
    VM_DELETE_FORCE("vm.delete.force", NotificationImportance.HIGH),
    VM_DELETE_COMPLETED("vm.delete.completed", NotificationImportance.NORMAL),
    DOMAIN_CONNECT_DONE("domain.connect.done", NotificationImportance.NORMAL),
    DOMAIN_CONNECT_FAILED("domain.connect.failed", NotificationImportance.HIGH),
    CERT_FAILURE("cert.failure", NotificationImportance.HIGH),
    VM_EXPIRY_NOTICE("vm.expiry.notice", NotificationImportance.NORMAL),
    VM_EXPIRY_STOPPED("vm.expiry.stopped", NotificationImportance.HIGH),
    ANNOUNCEMENT("announcement", NotificationImportance.NORMAL),

    // M6 (contract v0.9.0) — all M6 events are pre-registered at kickoff so
    // parallel lanes never edit this enum concurrently (W0 decision).
    ACCOUNT_PASSWORD_CHANGED("account.password.changed", NotificationImportance.HIGH),
    ACCOUNT_DISABLED("account.disabled", NotificationImportance.HIGH),
    ACCOUNT_ENABLED("account.enabled", NotificationImportance.NORMAL),
    ACCOUNT_WITHDRAWN("account.withdrawn", NotificationImportance.NORMAL),
    ACCOUNT_MFA_ENROLLED("account.mfa.enrolled", NotificationImportance.HIGH),
    ACCOUNT_MFA_DISABLED("account.mfa.disabled", NotificationImportance.HIGH),
    ACCOUNT_MFA_RESET("account.mfa.reset", NotificationImportance.HIGH),
    GROUP_DELETED("group.deleted", NotificationImportance.NORMAL);

    private final String id;
    private final NotificationImportance defaultImportance;

    NotificationEvent(String id, NotificationImportance defaultImportance) {
        this.id = id;
        this.defaultImportance = defaultImportance;
    }

    public String id() {
        return id;
    }

    public NotificationImportance defaultImportance() {
        return defaultImportance;
    }

}
