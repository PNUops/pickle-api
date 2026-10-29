package kr.ac.pusan.pickle.observation.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** One immutable producer sample; delivery timestamps are not sample identity. */
public record InferenceObservationRequest(
        int version,
        UUID sourceId,
        UUID epoch,
        long sequence,
        Instant observedAt,
        Instant reporterReceivedAt,
        Host host,
        JsonNode inference) {

    public record Host(String state, Map<String, Measurement> metrics) {
    }

    public record Measurement(String state, @Nullable Double value) {
    }
}
