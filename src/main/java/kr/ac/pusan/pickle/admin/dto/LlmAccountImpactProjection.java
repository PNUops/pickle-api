package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(description = "전체 제안의 계정별 스냅샷이에요. 계정 금융 조회 권한이 필요하며 각 대상 키의 관측과 금융도 같은 출처에서 함께 허용돼야 제공해요. 미제공을 이유로 기존에 허용된 명령을 막지 않으며 새 금융 CAS나 잔액 예약을 뜻하지 않아요.")
public record LlmAccountImpactProjection(LlmAccountImpactState state, Instant observedAt,
        @Nullable String basisFingerprint, @Nullable List<LlmAccountImpact> accounts) {
    public enum LlmAccountImpactState { NOT_APPLICABLE, NOT_PROVIDED, AVAILABLE }
    public static LlmAccountImpactProjection notApplicable(Instant now) {
        return new LlmAccountImpactProjection(LlmAccountImpactState.NOT_APPLICABLE, now, null, null);
    }
    public static LlmAccountImpactProjection notProvided(Instant now) {
        return new LlmAccountImpactProjection(LlmAccountImpactState.NOT_PROVIDED, now, null, null);
    }
}
