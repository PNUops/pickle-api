package kr.ac.pusan.pickle.llm.dto;

import java.util.List;

/**
 * Gateway → api usage batch (internal contract). Delivery is at-least-once
 * from a persisted checkpoint, so duplicates are normal, not a malfunction.
 *
 * <p><b>Deliberately no bean-validation constraints anywhere on this type.</b>
 * The gateway reads 400/409/413/422 as "this batch is the problem", skips the
 * batch and moves its checkpoint past it — those events are then gone for
 * good. A single malformed event must therefore never surface as a 4xx: the
 * events are validated one by one in the service, bad ones are counted into
 * {@code rejected}, and the batch as a whole answers 2xx.</p>
 *
 * <p>{@code requestedAt} is a string parsed per event for the same reason —
 * a timestamp Jackson cannot bind would fail the whole batch at
 * deserialization, before any per-event handling could run.</p>
 */
public record LlmUsageRequest(
        String agentVersion,
        List<UsageEvent> events) {

    /**
     * One spooled event, verbatim. Only {@code eventUuid}, {@code status},
     * {@code inputTokens}, {@code outputTokens}, {@code latencyMs} and
     * {@code requestedAt} are always present; the rest are omitted when zero
     * or empty. {@code eventUuid} is an opaque string of at most 64
     * characters, NOT necessarily a UUID (the gateway falls back to a
     * timestamp-derived id when its random source fails). {@code keyId} may
     * be absent — several error paths never resolve a key, and those events
     * are kept, not dropped.
     */
    public record UsageEvent(
            String eventUuid,
            Long generation,
            String keyId,
            String publicModelName,
            String upstreamRef,
            Integer attempts,
            String status,
            String errorType,
            Integer inputTokens,
            Integer outputTokens,
            Boolean estimated,
            Long latencyMs,
            Long ttftMs,
            String requestedAt) {
    }
}
