package kr.ac.pusan.pickle.publishing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

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
        @Schema(description = "true면 이 루트 아래 이름 신청이 접수와 동시에 승인됩니다. "
                + "false면 승인 큐를 탑니다. **지금 이 값이 다스리는 것은 외부 도메인 발급뿐이고 "
                + "가상머신 서브도메인 공개는 아직 아닙니다.**")
        boolean autoApprove,
        @Schema(description = "이 루트 아래 지금 서 있는 외부 도메인 수")
        long issuedNames) {
}
