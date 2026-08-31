package kr.ac.pusan.pickle.admin.dto;

import java.util.List;

/** One bounded level of the scope-aware consumption drill-down. */
public record LlmUsageConsumersResponse(
        LlmUsageConsumerLevel level,
        List<LlmUsageConsumerResponse> items,
        long totalItems,
        boolean truncated) {
}
