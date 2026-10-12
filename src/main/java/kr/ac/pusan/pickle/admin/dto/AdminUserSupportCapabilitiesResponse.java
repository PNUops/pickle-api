package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Actor capabilities for the account shown in a support detail. */
public record AdminUserSupportCapabilitiesResponse(
        @Schema(description = "학생 지원 프로필 조회 가능 여부") boolean profileVisible,
        @Schema(description = "프로필 정정과 정정 영향 미리보기 가능 여부") boolean canCorrectProfile,
        @Schema(description = "계정 활성화와 활성화 영향 미리보기 가능 여부") boolean canEnableAccount) { }
