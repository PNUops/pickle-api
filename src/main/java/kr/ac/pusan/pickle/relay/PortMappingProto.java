package kr.ac.pusan.pickle.relay;

import java.util.Locale;

/**
 * Mapping protocol. The PUBLIC contract ({@code /api/v1}) uses the enum names
 * (TCP | UDP) like every other domain enum; the DB stores the same form. The
 * INTERNAL sync response is the one deliberate exception: the agent's strict
 * parser accepts only lowercase {@code "tcp"}/{@code "udp"} (frozen snapshot
 * record), so the sync path serializes {@link #wire()} — never {@code name()}.
 */
public enum PortMappingProto {
    TCP,
    UDP;

    /** Frozen sync-record form: lowercase, exactly what the agent applies. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
