package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "현재 캐시의 계정 배정과 같은 기준으로 계산한 변경 후 값이에요. 실제 다음 리셋이나 공급자 적용, 계정 전체 실측 또는 보장된 상한을 뜻하지 않아요.")
public record LlmAccountImpact(UUID accountId, String accountName, int changedKeys,
        LlmAccountImpactAmounts before, LlmAccountImpactAmounts after,
        @Nullable BigDecimal balance, @Nullable Instant balanceObservedAt,
        @Nullable String balanceError, @Nullable Boolean overAllocated, LlmAccountImpactQuality quality) { }
