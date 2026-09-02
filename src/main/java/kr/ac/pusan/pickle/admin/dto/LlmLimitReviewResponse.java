package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import org.jspecify.annotations.Nullable;

/** Current limit state and exact recent limit pressure for one key. */
public record LlmLimitReviewResponse(
        UUID keyId,
        String keyName,
        UUID orgId,
        String orgName,
        UUID workspaceId,
        String workspaceName,
        LlmApiKeyStatus status,
        @Nullable Long dailyTokens,
        long todayTokens,
        @Schema(description = "요청 당시 budget axis가 보고되지 않은 오늘 입력+출력 token")
        long todayUnknownAxisTokens,
        boolean quotaExhausted,
        BigDecimal creditLimit,
        @Nullable CreditLimitReset creditLimitReset,
        @Nullable BigDecimal creditUsage,
        @Nullable BigDecimal creditLimitRemaining,
        @Nullable Instant creditUsageAt,
        boolean creditAxisConnected,
        @Schema(description = "연결된 사업 계정. 연결 전이면 null")
        @Nullable UUID openrouterAccountId,
        @Nullable String openrouterAccountName,
        List<LlmLimitPressureResponse> pressure) {
}
