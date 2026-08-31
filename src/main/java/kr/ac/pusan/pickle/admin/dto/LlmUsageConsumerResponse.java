package kr.ac.pusan.pickle.admin.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Usage for one organisation, workspace or key at the selected drill level. */
public record LlmUsageConsumerResponse(
        @Nullable UUID orgId,
        @Nullable String orgName,
        @Nullable UUID workspaceId,
        @Nullable String workspaceName,
        @Nullable UUID keyId,
        @Nullable String keyName,
        long requests,
        long inputTokens,
        long outputTokens) {
}
