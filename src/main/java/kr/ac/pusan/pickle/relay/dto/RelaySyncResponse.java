package kr.ac.pusan.pickle.relay.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.networkpolicy.SourcePolicyWire;

/**
 * api → agent sync answer. A legacy unchanged state answers
 * {@code {"generation": N}} with {@code mappings} omitted. Once retirement is
 * armed, every answer carries the complete managed snapshot, including explicit
 * mappings and retirements arrays plus the acknowledgement cursor, so the
 * consumer can verify its canonical snapshot hash even at a stable generation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelaySyncResponse(
        long generation,
        List<MappingSnapshot> mappings,
        List<RetirementSnapshot> retirements,
        Long acknowledgedRetirementHighWater) {

    public RelaySyncResponse(long generation, List<MappingSnapshot> mappings) {
        this(generation, mappings, null, null);
    }

    /**
     * One desired mapping, byte-for-byte the applier's input (frozen record
     * shape). Guard fields are omitted when the column is null (agent
     * default); {@code 0} means the guard is disabled.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MappingSnapshot(
            long id,
            String proto,
            int publicPort,
            String targetAddr,
            int targetPort,
            Integer ctMax,
            Integer newConnRate,
            Integer newConnBurst,
            Integer perSourceRate,
            Integer perSourceBurst,
            SourcePolicyWire sourcePolicy,
            Long flowMark) {

        public MappingSnapshot(long id, String proto, int publicPort, String targetAddr,
                int targetPort, Integer ctMax, Integer newConnRate, Integer newConnBurst,
                Integer perSourceRate, Integer perSourceBurst, SourcePolicyWire sourcePolicy) {
            this(id, proto, publicPort, targetAddr, targetPort, ctMax, newConnRate,
                    newConnBurst, perSourceRate, perSourceBurst, sourcePolicy, null);
        }
    }

    public record RetirementSnapshot(
            UUID retirementId,
            long mappingId,
            long generation,
            String proto,
            int publicPort,
            String targetAddr,
            int targetPort,
            long flowMark,
            String tupleHash) {
    }
}
