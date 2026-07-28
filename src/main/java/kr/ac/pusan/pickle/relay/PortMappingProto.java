package kr.ac.pusan.pickle.relay;

/**
 * Mapping protocol. The constant names ARE the wire and database form
 * (lowercase, matching the frozen sync-snapshot record and the
 * {@code port_mappings.proto} check constraint), so the value flows from the
 * request body through the row to the agent without any case conversion.
 */
public enum PortMappingProto {
    tcp,
    udp
}
