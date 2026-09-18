package kr.ac.pusan.pickle.relay;

/** Internal consumer lifecycle; public status remains user-facing intent/readiness. */
public enum PortMappingDeliveryState {
    LEGACY,
    PENDING,
    ACTIVE,
    RETIRING,
    SUSPENDED
}
