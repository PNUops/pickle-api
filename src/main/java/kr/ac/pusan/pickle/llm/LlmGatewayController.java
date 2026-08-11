package kr.ac.pusan.pickle.llm;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import kr.ac.pusan.pickle.llm.dto.LlmBodiesRequest;
import kr.ac.pusan.pickle.llm.dto.LlmSyncRequest;
import kr.ac.pusan.pickle.llm.dto.LlmSyncResponse;
import kr.ac.pusan.pickle.llm.dto.LlmUsageRequest;
import kr.ac.pusan.pickle.llm.dto.LlmUsageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
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

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayController.class);

    private final LlmSyncService llmSyncService;
    private final LlmUsageService llmUsageService;

    public LlmGatewayController(LlmSyncService llmSyncService, LlmUsageService llmUsageService) {
        this.llmSyncService = llmSyncService;
        this.llmUsageService = llmUsageService;
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
     * Accepts and counts opted-in prompt/response records, and deliberately
     * stores nothing. That is a chosen state, not an unfinished one: the
     * storage, its encryption key and its retention policy are a later round
     * gated on a privacy-policy revision, and until that decision exists no
     * captured text may be persisted anywhere on this side. Answering 2xx
     * keeps the gateway's bounded in-memory queue draining (it drops rather
     * than blocks, and never spools these to its disk), so turning storage on
     * later is purely an api-side change.
     */
    @PostMapping("/bodies")
    public ResponseEntity<Void> bodies(@RequestBody LlmBodiesRequest request) {
        int count = request.records() == null ? 0 : request.records().size();
        log.info("LLM bodies batch: accepted and discarded {} records (storage deferred "
                + "pending the privacy-policy decision)", count);
        return ResponseEntity.noContent().build();
    }
}
