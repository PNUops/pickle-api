package kr.ac.pusan.pickle.llm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.LlmAsyncObjectKind;

/**
 * Gateway → api: the vendor accepted the submit and named its object.
 * {@code sizeBytes} and {@code expiresAt} are optional and only stored.
 */
public record LlmObjectBindRequest(
        String agentVersion,
        @NotNull UUID keyId,
        @NotNull UUID intentId,
        @NotNull LlmAsyncObjectKind kind,
        @NotBlank @Size(max = 255) String objectId,
        @PositiveOrZero Long sizeBytes,
        Instant expiresAt) {
}
