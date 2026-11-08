package kr.ac.pusan.pickle.admin.dto;

import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What an account has already promised out, as facts rather than a judgement.
 *
 * <p>Nothing here is nullable. The rule that an unobserved credits figure stays
 * null rather than becoming zero belongs to the balance, which we learn from the
 * vendor and may simply not know yet; an account with no keys on it has an
 * allocation of zero and we know that for certain.
 */
@Schema(description = "이 사업 계정에 연결된 살아 있는 키의 금액 한도 합계")
public record OpenRouterAccountAllocationResponse(
        @Schema(description = "전체 한도를 더한 값이에요. 이미 쓴 금액을 포함한 약속량이므로 "
                + "잔액과 직접 비교하지 않아요.")
        BigDecimal committedCreditLimit,
        @Schema(description = "리셋 창이 없는 키의 한도 합계")
        BigDecimal committedTotalCap,
        @Schema(description = "일간 리셋 키의 한도 합계이며 창마다 다시 채워져요.")
        BigDecimal committedDaily,
        @Schema(description = "주간 리셋 키의 한도 합계이며 창마다 다시 채워져요.")
        BigDecimal committedWeekly,
        @Schema(description = "월간 리셋 키의 한도 합계이며 창마다 다시 채워져요.")
        BigDecimal committedMonthly,
        @Schema(description = "금액 한도가 0보다 큰 살아 있는 키 수예요. 한도 0인 키는 "
                + "약속량이 없으므로 세지 않아요.")
        long committedKeyCount,
        @Schema(description = "키마다 금액 한도에서 공급자 누적 사용액을 빼고 음수는 0으로 두어 "
                + "합산한 캐시 추정치예요. 사용액 미보고 키는 한도 전액을 포함하며 대사는 30분 주기로 시도해요. "
                + "현재 리셋 창의 실제 잔여 한도와는 달라요.")
        BigDecimal remainingCommitment,
        @Schema(description = "살아 있는 키의 리셋을 반영한 누적 사용액 합계예요. 현재 창의 사용액이 "
                + "아닌 사업 계정 기준점 이후의 누계이며 폐기되거나 만료된 키의 몫은 빠져 있어 "
                + "credits.managedUsageSinceBaseline과는 다른 값이에요.")
        BigDecimal committedUsage,
        @Schema(description = "금액 한도가 0보다 크지만 아직 프로비저닝되지 않아 공급자에 한도가 "
                + "반영되지 않은 키 수")
        long awaitingProvisionKeyCount,
        @Schema(description = "프로비저닝됐지만 사용액 보고가 없는 키 수예요. 0이 아니면 "
                + "remainingCommitment의 해당 부분은 실측값이 아닌 한도 전액이에요.")
        long usageUnreportedKeyCount) {
}
