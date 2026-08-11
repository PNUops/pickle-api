package kr.ac.pusan.pickle.llm.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * Gateway → api sync poll (internal contract, not part of the public one).
 * This request is the only channel from the gateway to the api — the api
 * never calls the gateway — so everything on it is a claim to display, never
 * to act on: strings are control-stripped and truncated server-side before
 * touching any row, and gauges are stored as reported.
 *
 * <p>Only {@code appliedGeneration}, {@code supportedFormat} and
 * {@code inFlight} are always present; every other member is omitted when
 * zero or empty, so an ordinary poll from a healthy gateway carries a handful
 * of fields. Deliberately no rejecting constraints beyond the three required
 * members: a 400 here would repeat on every 5-second poll and freeze the
 * authorization channel over a cosmetic report field.</p>
 */
public record LlmSyncRequest(
        @NotNull @Min(0) Long appliedGeneration,
        @NotNull Integer supportedFormat,
        String agentVersion,
        Instant startedAt,
        @NotNull Integer inFlight,
        Integer maxInFlight,
        List<String> upstreamRefs,
        Integer rejectedEntries,
        Long reloadFailures,
        String lastError,
        Long bodiesDropped,
        Long usageShipFailures,
        Long spoolWriteFailures) {
}
