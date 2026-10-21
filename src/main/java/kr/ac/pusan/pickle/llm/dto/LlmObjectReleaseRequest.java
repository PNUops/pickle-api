package kr.ac.pusan.pickle.llm.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.LlmAsyncObjectKind;
import kr.ac.pusan.pickle.llm.LlmObjectReleaseReason;

/**
 * Gateway → api: release an owned object ({@code objectId} with
 * {@code DELETED} or {@code VENDOR_GONE}) or an unbound intent
 * ({@code intentId} with {@code NOT_CREATED}). Exactly one of the two ids.
 */
public record LlmObjectReleaseRequest(
        String agentVersion,
        @NotNull UUID keyId,
        @NotNull LlmAsyncObjectKind kind,
        @Size(min = 1, max = 255) String objectId,
        UUID intentId,
        @NotNull LlmObjectReleaseReason reason) {
}
