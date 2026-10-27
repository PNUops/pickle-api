package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCreditsFreshness;
import org.jspecify.annotations.Nullable;

@Schema(description = "서버 cache의 출처·시각과 독립적인 품질입니다. reset 변경, 변경하지 않은 미보고 키, stale이 함께 존재할 수 있습니다. 창이 있는 vendor 사용액의 자연 리셋 정렬은 확인되지 않았으며 다음 대사 전에는 남은 배정을 과소 표시할 수도 있습니다.")
public record LlmAccountImpactQuality(String source, Instant observedAt,
        OpenRouterCreditsFreshness creditsFreshness, OpenRouterCreditsFreshness keyMetersFreshness,
        @Nullable Instant creditsLastSuccessAt, @Nullable Instant creditsLastAttemptAt,
        @Nullable Instant keyMetersLastSuccessAt, @Nullable Instant keyMetersLastAttemptAt,
        @Nullable String keyMetersError, boolean balanceUnreported,
        long beforeUsageUnreportedKeys, long afterUsageUnreportedKeys,
        long beforeAwaitingProvisionKeys, long afterAwaitingProvisionKeys,
        long changedPeriodKeys, boolean periodBasisMismatch,
        boolean windowedCommitmentsPresent, boolean vendorWindowAlignmentUnconfirmed) { }
