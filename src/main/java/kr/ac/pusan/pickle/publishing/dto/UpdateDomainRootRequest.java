package kr.ac.pusan.pickle.publishing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Contract schema {@code UpdateDomainRootRequest}: the root's issuance policy. */
public record UpdateDomainRootRequest(
        @NotNull(message = "승인 정책을 지정해 주세요.")
        @Schema(description = "true면 자동 승인, false면 승인 필요.")
        Boolean autoApprove) {
}
