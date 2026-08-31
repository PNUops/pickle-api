package kr.ac.pusan.pickle.admin.dto;

import java.util.List;

/** Fixed decision windows and the selected daily series. */
public record LlmUsageDemandResponse(
        List<LlmUsageWindowResponse> windows,
        List<LlmUsageDailyPointResponse> daily) {
}
