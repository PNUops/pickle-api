package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "현재 키의 허용된 제안을 DB cache로 계산한 정보입니다. 읽기이며 저장·계정 결합·세대 변경·외부 호출을 하지 않습니다. accountImpact 미제공은 기존 허용 명령을 막는 새 권한 조건이 아닙니다.")
public record LlmKeyLimitsProjection(Instant configurationObservedAt, LlmKeyLimitsDraft draft,
        LlmAccountImpactProjection accountImpact) {
    public LlmKeyLimitsProjection withoutAccountImpact() {
        return new LlmKeyLimitsProjection(configurationObservedAt, draft,
                LlmAccountImpactProjection.notProvided(accountImpact.observedAt()));
    }
}
