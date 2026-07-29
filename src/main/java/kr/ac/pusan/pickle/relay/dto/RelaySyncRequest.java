package kr.ac.pusan.pickle.relay.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Agent → api sync report (internal, not part of the public contract). All
 * free-text fields are claims by the relay: they are length-capped here and
 * additionally control-stripped/truncated server-side before touching any row
 * or audit entry.
 *
 * <p>Counter readings are cumulative since agent start. A reading below the
 * previously stored one ALWAYS means the agent restarted — the server
 * re-baselines (delta = raw) and never computes a negative delta.</p>
 *
 * <p>{@code counters} deliberately carries NO rejecting size constraint: the
 * agent reports one row per live mapping, so any cap here would turn a busy
 * relay's report into a 400 and take the desired-state channel (suspend,
 * delete, auto-suspend, last-contact) down with it. Volume is bounded by the
 * body cap, and the server processes only a bounded prefix of the rows.</p>
 */
public record RelaySyncRequest(
        @NotNull @Min(0) Long appliedGeneration,
        @Size(max = 128) String agentVersion,
        @Size(max = 8) List<@Valid ReportedMappingError> lastError,
        List<@Valid ReportedMappingCounters> counters) {

    /** One agent-side apply failure; {@code mappingId} is optional. */
    public record ReportedMappingError(
            Long mappingId,
            @Size(max = 4096) String message) {
    }

    /** Raw per-mapping counter readings (cumulative since agent start). */
    public record ReportedMappingCounters(
            @NotNull Long mappingId,
            Long newConns,
            Long inPackets,
            Long inBytes,
            Long outPackets,
            Long outBytes,
            Long rateDropped,
            Long connDropped,
            Long perSourceDropped) {
    }
}
