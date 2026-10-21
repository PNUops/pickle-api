package kr.ac.pusan.pickle.llm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.LlmAsyncObjectKind;

/**
 * Gateway → api: record that this key is about to submit an async object.
 * Sent before the vendor call, so a refusal here means nothing was spent.
 */
public record LlmObjectIntentRequest(
        String agentVersion,
        @NotNull UUID keyId,
        @NotNull LlmAsyncObjectKind kind,
        @NotBlank @Size(max = 255) String eventId) {
}
