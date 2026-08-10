package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import java.util.List;

/** Contract schema {@code NodeMetrics} (SYS tier). */
public record NodeMetricsResponse(
        String timeframe,
        Instant fetchedAt,
        List<NodeMetricPointResponse> points) {
}
