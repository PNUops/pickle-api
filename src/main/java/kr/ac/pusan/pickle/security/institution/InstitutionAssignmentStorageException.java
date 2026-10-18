package kr.ac.pusan.pickle.security.institution;

/** An internal persistence failure, never an instruction to fall back to role authorization. */
public final class InstitutionAssignmentStorageException extends IllegalStateException {
    public enum Reason {
        UNCONVERTED, CORRUPT_PAYLOAD, ROLE_MISMATCH, REVISION_CONFLICT, MISSING_MEMBERSHIP, TRANSACTION_REQUIRED
    }

    private final Reason reason;

    public InstitutionAssignmentStorageException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public InstitutionAssignmentStorageException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
