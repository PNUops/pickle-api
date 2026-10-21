package kr.ac.pusan.pickle.llm;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import kr.ac.pusan.pickle.llm.dto.LlmObjectBindRequest;
import kr.ac.pusan.pickle.llm.dto.LlmObjectBindResponse;
import kr.ac.pusan.pickle.llm.dto.LlmObjectIntentRequest;
import kr.ac.pusan.pickle.llm.dto.LlmObjectIntentResponse;
import kr.ac.pusan.pickle.llm.dto.LlmObjectLookupRequest;
import kr.ac.pusan.pickle.llm.dto.LlmObjectLookupResponse;
import kr.ac.pusan.pickle.llm.dto.LlmObjectRefusal;
import kr.ac.pusan.pickle.llm.dto.LlmObjectReleaseRequest;
import kr.ac.pusan.pickle.llm.dto.LlmObjectReleaseResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Async-object ownership calls on the LLM gateway link. Auth is the same
 * filter as the rest of {@code /internal/llm/**}; by the time a handler runs
 * the caller is the gateway. Unlike usage and bodies these take {@code @Valid}:
 * the gateway calls them synchronously on a client's request, never batches
 * them, and a malformed call is a gateway bug worth refusing loudly.
 */
@Hidden
@RestController
@RequestMapping("/internal/llm/objects")
public class LlmObjectOwnershipController {

    private final LlmAsyncObjectService service;

    public LlmObjectOwnershipController(LlmAsyncObjectService service) {
        this.service = service;
    }

    @PostMapping("/intents")
    @ResponseStatus(HttpStatus.CREATED)
    public LlmObjectIntentResponse intents(@Valid @RequestBody LlmObjectIntentRequest request) {
        return service.intent(request);
    }

    @PostMapping("/bind")
    public LlmObjectBindResponse bind(@Valid @RequestBody LlmObjectBindRequest request) {
        return service.bind(request);
    }

    @PostMapping("/lookup")
    public LlmObjectLookupResponse lookup(@Valid @RequestBody LlmObjectLookupRequest request) {
        return service.lookup(request);
    }

    @PostMapping("/release")
    public LlmObjectReleaseResponse release(@Valid @RequestBody LlmObjectReleaseRequest request) {
        return service.release(request);
    }

    @ExceptionHandler(LlmObjectRefusedException.class)
    ResponseEntity<LlmObjectRefusal> refused(LlmObjectRefusedException e) {
        return ResponseEntity.status(e.status()).body(new LlmObjectRefusal(e.code()));
    }
}
