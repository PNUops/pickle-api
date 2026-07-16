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

    REQUEST_SUBMITTED("request.submitted", NotificationImportance.NORMAL, true),
    REQUEST_APPROVED("request.approved", NotificationImportance.NORMAL, true),
    REQUEST_REJECTED("request.rejected", NotificationImportance.NORMAL, true),
    VM_CREATE_DONE("vm.create.done", NotificationImportance.NORMAL, true),
    VM_CREATE_FAILED("vm.create.failed", NotificationImportance.HIGH, true),
    VM_DELETE_ACCEPTED("vm.delete.accepted", NotificationImportance.NORMAL, true),
    VM_DELETE_SCHEDULED("vm.delete.scheduled", NotificationImportance.HIGH, true),
    VM_DELETE_CANCELED("vm.delete.canceled", NotificationImportance.NORMAL, true),
    VM_DELETE_FORCE("vm.delete.force", NotificationImportance.HIGH, true),
    VM_DELETE_COMPLETED("vm.delete.completed", NotificationImportance.NORMAL, true),
    DOMAIN_CONNECT_DONE("domain.connect.done", NotificationImportance.NORMAL, true),
    DOMAIN_CONNECT_FAILED("domain.connect.failed", NotificationImportance.HIGH, true),
    CERT_FAILURE("cert.failure", NotificationImportance.HIGH, true),
    VM_EXPIRY_NOTICE("vm.expiry.notice", NotificationImportance.NORMAL, true),
    VM_EXPIRY_STOPPED("vm.expiry.stopped", NotificationImportance.HIGH, true),
    ANNOUNCEMENT("announcement", NotificationImportance.NORMAL, true);

    private final String id;
    private final NotificationImportance defaultImportance;
    private final boolean emailEnabled;

    NotificationEvent(String id, NotificationImportance defaultImportance, boolean emailEnabled) {
        this.id = id;
        this.defaultImportance = defaultImportance;
        this.emailEnabled = emailEnabled;
    }

    public String id() {
        return id;
    }

    public NotificationImportance defaultImportance() {
        return defaultImportance;
    }

    public boolean emailEnabled() {
        return emailEnabled;
    }
}
