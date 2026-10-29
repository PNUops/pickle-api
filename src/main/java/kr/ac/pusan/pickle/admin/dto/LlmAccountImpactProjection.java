package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(description = "전체 제안의 계정별 정보 스냅샷입니다. 계정 금융과 대상 키 관측·금융의 완전한 source가 없으면 미제공하며, 이를 이유로 기존 허용 명령을 차단하지 않습니다. 새 금융 CAS나 잔액 예약이 아닙니다.")
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
