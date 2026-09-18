package kr.ac.pusan.pickle.relay.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

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
        @Size(max = 32) List<@NotBlank @Size(max = 64) String> capabilities,
        UUID retirementLedgerId,
        @Min(0) Long mappingIdHighWater,
        @Min(0) @Max(4294967295L) Long flowMarkHighWater,
        @Min(0) Long managedGenerationHighWater,
        @Min(0) Long retirementHighWater,
        @Size(max = 65536) List<@Valid RetirementReceipt> retirementReceipts,
        @Size(max = 8) List<@Valid ReportedMappingError> lastError,
        List<@Valid ReportedMappingCounters> counters) {

    public RelaySyncRequest(Long appliedGeneration, String agentVersion,
            List<String> capabilities, List<ReportedMappingError> lastError,
            List<ReportedMappingCounters> counters) {
        this(appliedGeneration, agentVersion, capabilities, null, null, null, null, null,
                null, lastError, counters);
    }

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

    public record RetirementReceipt(
            @NotNull UUID retirementId,
            @NotNull @Min(1) Long generation,
            @NotNull @Min(1) Long mappingId,
            @NotNull @Min(1) @Max(4294967295L) Long flowMark,
            @NotBlank @Size(min = 64, max = 64) String tupleHash,
            @NotBlank String state) {
    }
}
