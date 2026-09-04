package kr.ac.pusan.pickle.llm;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import kr.ac.pusan.pickle.llm.dto.LlmBodiesRequest;
import kr.ac.pusan.pickle.llm.dto.LlmBodiesResponse;
import kr.ac.pusan.pickle.llm.dto.LlmSyncRequest;
import kr.ac.pusan.pickle.llm.dto.LlmSyncResponse;
import kr.ac.pusan.pickle.llm.dto.LlmUsageRequest;
import kr.ac.pusan.pickle.llm.dto.LlmUsageResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM gateway link endpoints (outside {@code /api/v1}, hidden from the public
 * contract). Auth lives entirely in {@link LlmGatewayAuthFilter} on the
 * dedicated {@code /internal/llm/**} chain: by the time a handler runs, the
 * caller IS the gateway (source pin + static bearer), so no principal is
 * consulted here.
 */
@Hidden
@RestController
@RequestMapping("/internal/llm")
public class LlmGatewayController {

    private final LlmSyncService llmSyncService;
    private final LlmUsageService llmUsageService;
    private final LlmBodyIngestService llmBodyIngestService;

    public LlmGatewayController(LlmSyncService llmSyncService, LlmUsageService llmUsageService,
            LlmBodyIngestService llmBodyIngestService) {
        this.llmSyncService = llmSyncService;
        this.llmUsageService = llmUsageService;
        this.llmBodyIngestService = llmBodyIngestService;
    }

    @PostMapping("/sync")
    public LlmSyncResponse sync(@Valid @RequestBody LlmSyncRequest request) {
        return llmSyncService.sync(request);
    }

    /**
     * No {@code @Valid} here, on purpose: a constraint violation would answer
     * 400, which the gateway reads as "this batch is the problem" — it skips
     * the batch, moves its checkpoint past it, and the events are gone.
     * Per-event validation lives in the service and reports through the
     * response tally instead.
     */
    @PostMapping("/usage")
    public LlmUsageResponse usage(@RequestBody LlmUsageRequest request) {
        return llmUsageService.ingest(request);
    }

    /**
     * Stores opted-in prompt and response records.
     *
     * <p>No {@code @Valid} here for the same reason as {@code /usage}, and the
     * cost of getting it wrong is higher: a non-2xx makes the gateway drop the
     * batch, and this channel keeps nothing on disk and never re-sends. A
     * refused batch is text destroyed. Per-record problems are counted into
     * the tally instead and the batch answers 2xx.</p>
     *
     * <p>The gateway discards this response body, so the tally is for this
     * side's log and its tests.</p>
     */
    @PostMapping("/bodies")
    public LlmBodiesResponse bodies(@RequestBody LlmBodiesRequest request) {
        return llmBodyIngestService.ingest(request);
    }
}
