package kr.ac.pusan.pickle.publishing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code AdminDomainRootView}: a root this platform issues
 * names under, and what it decides about them.
 */
public record AdminDomainRootView(
        @Schema(description = "루트 도메인", example = "pusan.dev")
        String rootDomain,
        @Schema(description = "이 루트 아래 이름이 속하는 기관")
        UUID orgId,
        String orgName,
        @Schema(description = "true면 이 루트 아래 이름 신청이 접수와 동시에 승인돼요. "
                + "false면 승인이 필요해요. 이 정책은 외부 도메인 발급에만 적용하고 "
                + "VM 서브도메인 공개에는 적용하지 않아요.")
        boolean autoApprove,
        @Schema(description = "이 루트 아래 남아 있는 외부 도메인 수")
        @Nullable Long issuedNames,
        boolean metadataVisible,
        java.util.Set<String> allowedWorkIds) {
}
