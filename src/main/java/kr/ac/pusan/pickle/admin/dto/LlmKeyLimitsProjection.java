package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "현재 키에서 허용된 제안을 저장된 값으로 계산한 정보예요. 조회로 저장이나 계정 연결, 세대 변경, 외부 호출을 실행하지 않아요. accountImpact를 제공하지 않더라도 기존에 허용된 명령을 막는 새 권한 조건이 생기지는 않아요.")
public record LlmKeyLimitsProjection(Instant configurationObservedAt, LlmKeyLimitsDraft draft,
        LlmAccountImpactProjection accountImpact) {
    public LlmKeyLimitsProjection withoutAccountImpact() {
        return new LlmKeyLimitsProjection(configurationObservedAt, draft,
                LlmAccountImpactProjection.notProvided(accountImpact.observedAt()));
    }
}
