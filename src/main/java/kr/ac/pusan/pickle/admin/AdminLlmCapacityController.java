package kr.ac.pusan.pickle.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminLlmCapacityResponse;
import kr.ac.pusan.pickle.admin.dto.AdminLlmCapacityResponse.CapacityGroup;
import kr.ac.pusan.pickle.admin.dto.AdminLlmCapacityResponse.CapacityReasonCategory;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only demand over one explicit half-open window. */
@Tag(name = "admin", description = "관리자 API")
@RestController
@RequestMapping("/api/v1/admin/llm/capacity")
@PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
public class AdminLlmCapacityController {
    private final AdminLlmCapacityService service;

    public AdminLlmCapacityController(AdminLlmCapacityService service) { this.service = service; }

    @GetMapping
    @Operation(operationId = "getAdminLlmCapacity", summary = "관리자 LLM 수요와 한도 판단 자료",
            description = "같은 절대 기간의 보존된 raw event에서 수요·거절 사유·소비처를 조회합니다. "
                    + "기관·워크스페이스와 관측 업무 범위를 수요 집계 전에 적용합니다. 기존 SYS 전역 읽기에서는 설정된 고정 원천의 같은 기간 장비 표본과 품질을 독립 제공합니다. "
                    + "설치 완료·완전 수집·개인별 장비 귀속을 보장하지 않으며 외부 호출이나 변경을 실행하지 않습니다.")
    public AdminLlmCapacityResponse capacity(@AuthenticationPrincipal AuthenticatedUser actor,
            @Parameter(required = true, description = "포함하는 시작 시각(UTC Instant)") @RequestParam(required = false) @Nullable Instant from,
            @Parameter(required = true, description = "제외하는 종료 시각(UTC Instant). 미래 불가, 최대 기간 90일") @RequestParam(required = false) @Nullable Instant to,
            @RequestParam(required = false) UUID orgId, @RequestParam(required = false) UUID workspaceId,
            @Parameter(description = "현재 범위에서 보이는 등록 공급원의 공개 ID. 최종 처리 경로를 필터합니다.")
            @RequestParam(required = false) UUID upstreamId,
            @Parameter(description = "요청 당시 공개 모델 이름의 정확한 값") @RequestParam(required = false) String publicModelName,
            @Parameter(description = "정상 응답을 제외하고 기록된 errorType의 정확한 값") @RequestParam(required = false) String errorType,
            @Parameter(description = "정상 응답을 제외하고 errorType이 기록되지 않은 요청만 조회. errorType과 동시 지정할 수 없습니다.")
            @RequestParam(defaultValue = "false") boolean errorTypeMissing,
            @Parameter(description = "거절·오류 분류. 오류 유형 또는 미기록 필터와 함께 같은 사유 행의 소비처를 조회합니다.")
            @RequestParam(required = false) CapacityReasonCategory reasonCategory,
            @RequestParam(defaultValue = "WORKSPACE") CapacityGroup group,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @Parameter(description = "버킷 초. 생략하면 기간에 맞추며 최대 1440개 버킷을 반환합니다.")
            @RequestParam(required = false) Integer bucketSeconds,
            @Parameter(description = "SYS 전역 장비 표본의 고정 원천 공개 ID. 기관·공간·원인·공개 모델 수요 필터와 독립이며 현재 설정된 단일 원천만 지원합니다.")
            @RequestParam(required = false) @Nullable UUID telemetrySourceId) {
        List<FieldValidationError> errors = new ArrayList<>();
        if (from == null) { errors.add(new FieldValidationError("from", "조회 시작 시각을 보내 주세요.")); }
        if (to == null) { errors.add(new FieldValidationError("to", "조회 종료 시각을 보내 주세요.")); }
        if (!errors.isEmpty()) { throw ApiException.validationFailed(errors); }
        return service.get(actor, from, to, orgId, workspaceId, upstreamId, publicModelName, errorType, errorTypeMissing, reasonCategory, group, page, size, bucketSeconds, telemetrySourceId);
    }
}
