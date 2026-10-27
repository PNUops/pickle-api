package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "현재 읽을 수 있는 키 행의 탐색 수입니다. 전체 계정의 금융 합계, 승인 후 키 생성 대기 계획, 기간별 소비 키와는 다른 모집단입니다.")
public record LlmAccountKeyNavigation(Instant observedAt, String population,
        LlmNavigationCount linked, LlmNavigationCount committed,
        LlmNavigationCount awaitingProvision, LlmNavigationCount usageUnreported) {
    public LlmAccountKeyNavigation withoutFinance() {
        return new LlmAccountKeyNavigation(observedAt, population, linked,
                LlmNavigationCount.notProvided(), LlmNavigationCount.notProvided(), LlmNavigationCount.notProvided());
    }
}
