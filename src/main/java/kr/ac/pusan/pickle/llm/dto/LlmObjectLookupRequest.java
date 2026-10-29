package kr.ac.pusan.pickle.llm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.LlmAsyncObjectKind;

/** Gateway → api: which of these vendor ids does this key own. */
public record LlmObjectLookupRequest(
        String agentVersion,
        @NotNull UUID keyId,
        @NotNull LlmAsyncObjectKind kind,
        @NotNull @Size(min = 1, max = 100) List<@NotBlank @Size(max = 255) String> objectIds) {
}
