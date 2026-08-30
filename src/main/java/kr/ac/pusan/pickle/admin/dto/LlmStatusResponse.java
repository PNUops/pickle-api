package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import java.util.List;

/** Read-only current state of the LLM gateway and its upstreams. */
public record LlmStatusResponse(
        Instant observedAt,
        LlmGatewayStatusResponse gateway,
        List<LlmUpstreamStatusResponse> upstreams) {
}
