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
 * {@code inFlight} are unconditionally required across versions. A format-1
 * reporter also always sends {@code upstreamObservationFormat}, the authoritative
 * {@code upstreams} array (including explicit empty), and a successful queue scan's
 * {@code usageQueueObservedAt}; legacy gauges are otherwise omitted when zero or
 * empty. Deliberately no rejecting constraints beyond the three version-independent
 * required members: a 400 here would repeat on every 5-second poll and freeze the
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
        Long spoolWriteFailures,
        Integer upstreamObservationFormat,
        List<UpstreamObservation> upstreams,
        Instant lastUsageShipSuccessAt,
        Instant oldestUnshippedEventAt,
        Long queuedUsageEvents,
        Long queuedUsageBytes,
        Instant usageQueueObservedAt,
        Long usageQueueScanFailures) {

    /** One configured upstream's passive, active and catalogue observations. */
    public record UpstreamObservation(
            String ref,
            PassiveObservation passive,
            ActiveObservation active,
            CatalogObservation catalog) {
    }

    public record PassiveObservation(
            Instant lastAttemptAt,
            Instant lastSuccessAt,
            Instant lastFailureAt,
            String lastFailureType,
            Integer consecutiveFailures,
            Instant cooldownUntil) {
    }

    public record ActiveObservation(
            Instant lastAttemptAt,
            Instant lastSuccessAt,
            Instant lastFailureAt,
            String status,
            String lastFailureType,
            Integer intervalSeconds,
            Long latencyMs,
            Integer modelCount,
            Integer consecutiveFailures) {
    }

    public record CatalogObservation(
            String status,
            Integer expectedModelCount,
            Integer missingModelCount,
            Integer unexpectedModelCount,
            List<String> missingPublicModels) {
    }
}
