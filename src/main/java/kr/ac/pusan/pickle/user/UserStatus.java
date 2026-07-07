package kr.ac.pusan.pickle.user;

/** Account lifecycle status (contract schema {@code UserStatus}). */
public enum UserStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    DISABLED,
    WITHDRAWN
}
