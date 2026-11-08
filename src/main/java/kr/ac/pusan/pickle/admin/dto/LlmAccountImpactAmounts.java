package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

@Schema(description = "살아 있는 키의 명목 한도와 캐시에 기록된 남은 배정이에요. 사용액 미보고 키에는 전체 한도를 보수적으로 대입하지만 계정 전체 실측이나 상한을 뜻하지 않아요. 새 리셋 창에 이전 사용액을 적용할 수 없으면 변경 후 remainingCommitment는 null이에요.")
public record LlmAccountImpactAmounts(BigDecimal committedCreditLimit, BigDecimal committedTotalCap,
        BigDecimal committedDaily, BigDecimal committedWeekly, BigDecimal committedMonthly,
        long committedKeyCount, @Nullable BigDecimal remainingCommitment,
        long awaitingProvisionKeyCount, long usageUnreportedKeyCount) { }
