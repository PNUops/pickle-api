package kr.ac.pusan.pickle.admin.dto;

import java.util.List;

/** Bounded list of keys whose configured limits or real pressure merit review. */
public record LlmLimitReviewCollectionResponse(
        List<LlmLimitReviewResponse> items,
        long totalItems,
        boolean truncated) {
}
