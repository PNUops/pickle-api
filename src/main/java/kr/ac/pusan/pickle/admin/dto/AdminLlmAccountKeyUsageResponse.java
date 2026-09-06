package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code AdminLlmAccountKeyUsage}: one key's share of what a
 * business account was used for.
 *
 * <p>Only keys that were actually called appear. A linked key that made no
 * request in the window is absent rather than a zero row, because the question
 * this table answers is where the money went, and a list of keys that spent
 * nothing does not answer it. The count of linked keys travels on the response
 * beside this list, so a reader can still see how much of the account is idle.
 */
public record AdminLlmAccountKeyUsageResponse(
        UUID keyId,
        String keyName,
        long requests,
        long inputTokens,
        long outputTokens,
        @Schema(description = """
                이 키가 이 기간에 쓴 금액(USD). 가격이 붙은 요청이 없으면 null이며 0으로 \
                표시하지 않습니다.""")
        @Nullable BigDecimal attributedCostUsd,
        @Schema(description = "이 키의 요청 중 공급자가 금액을 알려 준 요청 수")
        long pricedRequests) {
}
