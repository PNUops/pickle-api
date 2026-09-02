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
@Schema(description = "이 account에 걸린 살아 있는 key의 금액 한도 합계")
public record OpenRouterAccountAllocationResponse(
        @Schema(description = "네 갈래 합계를 모두 더한 값. 이미 쓴 금액을 포함한 약속의 총량이라 "
                + "잔액과 직접 비교하지 않는다")
        BigDecimal committedCreditLimit,
        @Schema(description = "리셋 창이 없는 key의 한도 합. 한 번 나가고 끝나는 채무")
        BigDecimal committedTotalCap,
        @Schema(description = "일간 리셋 key의 한도 합. 창마다 다시 채워진다")
        BigDecimal committedDaily,
        @Schema(description = "주간 리셋 key의 한도 합. 창마다 다시 채워진다")
        BigDecimal committedWeekly,
        @Schema(description = "월간 리셋 key의 한도 합. 창마다 다시 채워진다")
        BigDecimal committedMonthly,
        @Schema(description = "금액 한도가 0보다 큰 살아 있는 key 수. 한도 0인 key는 돈을 "
                + "쥐고 있지 않으므로 세지 않는다")
        long committedKeyCount,
        @Schema(description = "Key마다 한도에서 현재 limit window 사용액을 뺀 값을 0에서 끊어 "
                + "더한 합. 잔액과 분모가 맞는 쪽은 이 값이다. 사용액은 30분 주기 대사값이라 "
                + "리셋 직후 다음 대사 전까지는 실제보다 작을 수 있다")
        BigDecimal remainingCommitment,
        @Schema(description = "살아 있는 key의 reset-aware 누적 사용액 합. 현재 창 사용액이 "
                + "아니라 account baseline 뒤 누계이며, 폐기와 만료 key의 몫은 빠져 있어 "
                + "credits.managedUsageSinceBaseline과 다른 수다")
        BigDecimal committedUsage,
        @Schema(description = "금액 한도가 0보다 큰데 아직 프로비저닝되지 않아 vendor에 한도가 "
                + "걸리지 않은 key 수")
        long awaitingProvisionKeyCount,
        @Schema(description = "프로비저닝됐지만 사용액 보고가 아직 없는 key 수. 0이 아니면 "
                + "remainingCommitment의 그만큼은 실측이 아니라 한도 전액")
        long usageUnreportedKeyCount) {
}
