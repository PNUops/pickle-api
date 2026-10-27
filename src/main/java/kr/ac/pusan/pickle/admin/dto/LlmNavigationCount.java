package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(description = "현재 허용된 키 집합의 정확한 탐색 수. 미제공은 null이며 실제 0과 다릅니다.")
public record LlmNavigationCount(LlmNavigationState state, @Nullable Long totalElements) {
    public enum LlmNavigationState { AVAILABLE, NOT_PROVIDED }
    public static LlmNavigationCount available(long count) {
        return new LlmNavigationCount(LlmNavigationState.AVAILABLE, count);
    }
    public static LlmNavigationCount notProvided() {
        return new LlmNavigationCount(LlmNavigationState.NOT_PROVIDED, null);
    }
}
