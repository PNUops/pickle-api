package kr.ac.pusan.pickle.relay.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * api → agent sync answer. Unchanged state answers {@code {"generation": N}}
 * with the {@code mappings} field OMITTED entirely (never an empty array —
 * the agent treats field presence as "replace your table with this");
 * changed state carries the full desired snapshot.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelaySyncResponse(
        long generation,
        List<MappingSnapshot> mappings) {

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
            Integer perSourceBurst) {
    }
}
