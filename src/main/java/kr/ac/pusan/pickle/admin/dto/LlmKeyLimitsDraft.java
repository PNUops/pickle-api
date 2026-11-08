package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import org.jspecify.annotations.Nullable;

@Schema(description = "서버가 평가한 열람 가능한 금액 제안과 항목별 지정 여부예요. creditLimitResetSet=false는 제안 없음이며 true이고 creditLimitReset=null이면 명시한 총액 한도를 뜻해요.")
public record LlmKeyLimitsDraft(boolean creditLimitSet, @Nullable BigDecimal creditLimit,
        boolean creditLimitResetSet, @Nullable CreditLimitReset creditLimitReset,
        @Nullable UUID openrouterAccountId) { }
