package kr.ac.pusan.pickle.observation.dto;

import java.time.Instant;
import java.util.UUID;

/** The authoritative content hash is computed by the server. */
public record InferenceObservationAck(UUID sourceId, UUID epoch, long sequence,
        String status, String contentSha256, Instant receivedAt) {
}
