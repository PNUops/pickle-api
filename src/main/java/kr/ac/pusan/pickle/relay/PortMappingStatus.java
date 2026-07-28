package kr.ac.pusan.pickle.relay;

/**
 * Server-side mapping state. SUSPENDED rows are excluded from the sync
 * snapshot (the agent only ever sees desired state; suspension semantics stay
 * on the server) and keep their public port reserved.
 */
public enum PortMappingStatus {
    ACTIVE,
    SUSPENDED
}
