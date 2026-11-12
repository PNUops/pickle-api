package kr.ac.pusan.pickle.publishing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code DomainRequestSpec}: what a domain request asked for
 * and what approving it issued.
 *
 * <p>{@code grantedFqdn} is not the two other fields joined together. A root's
 * default can move between submission and approval, so the name that was
 * created is its own fact and the row records it rather than leaving a reader
 * to reconstruct it. Null until the request is approved.</p>
 */
public record DomainRequestSpecResponse(
        @Schema(description = "신청한 이름(루트 바로 아래 한 라벨)", example = "myblog")
        String label,
        @Schema(description = "신청한 루트 도메인", example = "pusan.dev")
        String rootDomain,
        @Schema(description = "승인으로 발급한 도메인 이름이며, 승인 전에는 null이에요.",
                example = "myblog.pusan.dev")
        @Nullable String grantedFqdn) {
}
