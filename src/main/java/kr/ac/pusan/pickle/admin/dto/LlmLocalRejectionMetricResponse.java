package kr.ac.pusan.pickle.admin.dto;

/** Requests rejected before any upstream was contacted. */
public record LlmLocalRejectionMetricResponse(
        String errorType,
        long requests) {
}
