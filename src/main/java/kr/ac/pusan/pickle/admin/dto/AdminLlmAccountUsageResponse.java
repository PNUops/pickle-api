package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import kr.ac.pusan.pickle.llm.dto.LlmUsageCostPointResponse;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code AdminLlmAccountUsage}: what one business account was
 * used for over a window.
 *
 * <p>This is attribution and nothing else. It says where a total that is
 * already known went; the authoritative figures for what the account has spent
 * and what is left are the vendor's own, they arrive on the account response
 * beside this one, and the two must never be subtracted from each other. The
 * windows do not line up, and this side is structurally short in two ways.
 *
 * <p>The first: a request whose key never resolved carries no key, so it
 * belongs to no account and is counted by none of them. The second: a request
 * the vendor did not price is in {@code requests} and not in
 * {@code pricedRequests}, and its cost is absent rather than zero. Both are
 * reported rather than smoothed, which is why {@code pricedRequests} travels
 * beside every amount here.
 *
 * <p>Model names are read as written. The paid catalogue is passed through
 * without a row of our own, so joining a catalogue table to decorate a name
 * would drop exactly the traffic that has a price on it.
 */
public record AdminLlmAccountUsageResponse(
        LocalDate from,
        LocalDate to,
        @Schema(description = """
                이 기간에 이 계정에 귀속된 금액(USD). 공급자가 말하는 지출액이 아닙니다 — \
                그쪽은 계정 잔액과 함께 따로 오고 기간도 다릅니다. 가격이 붙은 요청이 하나도 \
                없으면 null입니다.""")
        @Nullable BigDecimal attributedCostUsd,
        @Schema(description = "이 기간에 이 계정의 키들이 보낸 요청 수")
        long requests,
        @Schema(description = "그중 공급자가 금액을 알려 준 요청 수")
        long pricedRequests,
        @Schema(description = "이 기간에 실제로 호출된 키 수")
        int keysUsed,
        @Schema(description = "이 계정에 연결된 키 수. keysUsed와 견주면 놀고 있는 키가 보입니다.")
        int keysLinked,
        @Schema(description = "하루 한 점, 오래된 날부터. 호출이 없던 날도 채워집니다.")
        List<LlmUsageCostPointResponse> points,
        @Schema(description = "이 기간에 실제로 호출된 키만, 금액이 큰 순")
        List<AdminLlmAccountKeyUsageResponse> keys) {
}
