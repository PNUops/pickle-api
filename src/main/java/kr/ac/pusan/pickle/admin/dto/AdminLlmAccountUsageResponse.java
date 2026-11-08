package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
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
                현재 이 계정에 연결된 키의 선택 기간 금액이에요(USD). 공급자의 지출액과는 집계 기간과 출처가 달라요. 가격이 확인된 요청이 없으면 null이에요.""")
        @Nullable BigDecimal attributedCostUsd,
        @Schema(description = "이 기간에 이 계정의 키들이 보낸 요청 수")
        long requests,
        @Schema(description = "그중 공급자가 금액을 알려 준 요청 수")
        long pricedRequests,
        @Schema(description = "선택한 KST 일자별 rollup과 현재 계정 연결을 기준으로 한 전체 소비 키 수예요. 현재 페이지나 보존 원문 capacity의 키 수와는 다른 값이에요.")
        int keysUsed,
        @Schema(description = "현재 이 계정에 연결된 모든 상태의 키 수예요. 기간별 소비 키 수와 현재 열람 가능한 키 수는 각각 다른 모집단이에요.")
        int keysLinked,
        @Schema(description = "하루에 한 점씩 오래된 날부터 제공하며 호출이 없던 날도 채워요.")
        List<LlmUsageCostPointResponse> points,
        @Schema(description = "선택 기간에 실제로 호출한 키의 현재 페이지예요. 요청한 정렬을 따르며 기본은 공급자가 보고한 요청 금액순이에요. keysUsed는 현재 페이지의 수가 아니라 전체 해당 키 수예요.")
        List<AdminLlmAccountKeyUsageResponse> keys,
        Instant generatedAt, String timezone, String source,
        int page, int size, long totalKeyElements, int totalKeyPages, LlmConsumerSort sort) {
}
