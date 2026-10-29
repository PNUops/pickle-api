package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import org.jspecify.annotations.Nullable;

@Schema(description = "서버가 평가한 읽을 수 있는 금액 제안과 presence입니다. resetSet=false는 제안 없음, true이면서 reset=null은 명시한 총액 한도입니다. 화면의 현재 제안과 일치할 때만 결과를 표시합니다.")
public record LlmKeyLimitsDraft(boolean creditLimitSet, @Nullable BigDecimal creditLimit,
        boolean creditLimitResetSet, @Nullable CreditLimitReset creditLimitReset,
        @Nullable UUID openrouterAccountId) { }
