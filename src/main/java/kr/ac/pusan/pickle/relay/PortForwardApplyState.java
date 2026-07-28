package kr.ac.pusan.pickle.relay;

/**
 * Derived (never stored) relay-side apply state of a mapping: ACTIVE iff the
 * relay's {@code applied_generation} has reached the mapping's
 * {@code last_change_generation}; FAILED overrides when the relay's stored
 * {@code last_error} names this mapping's id; otherwise PENDING. SUSPENDED is
 * a separate server-side {@code status}, not an apply state.
 */
public enum PortForwardApplyState {
    PENDING,
    ACTIVE,
    FAILED
}
