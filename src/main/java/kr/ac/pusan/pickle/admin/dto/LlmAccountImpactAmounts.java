package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

@Schema(description = "현재 live 키의 명목 한도와 server-cached 남은 배정입니다. 미보고 usage에는 해당 키의 full cap을 보수적으로 대입하지만 계정 전체 실측·상한이라고 부르지 않습니다. 새 리셋 창에 옛 usage를 쓸 수 없으면 변경 후 remainingCommitment는 null입니다.")
public record LlmAccountImpactAmounts(BigDecimal committedCreditLimit, BigDecimal committedTotalCap,
        BigDecimal committedDaily, BigDecimal committedWeekly, BigDecimal committedMonthly,
        long committedKeyCount, @Nullable BigDecimal remainingCommitment,
        long awaitingProvisionKeyCount, long usageUnreportedKeyCount) { }
