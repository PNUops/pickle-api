package kr.ac.pusan.pickle.gpu;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.gpu.dto.DecideGpuReviewRequest;
import kr.ac.pusan.pickle.gpu.dto.ExtendGpuLeaseRequest;
import kr.ac.pusan.pickle.gpu.dto.GpuAllocationView;
import kr.ac.pusan.pickle.gpu.dto.GpuReasonRequest;
import kr.ac.pusan.pickle.gpu.dto.GpuReclaimReviewView;
import kr.ac.pusan.pickle.gpu.dto.GpuView;
import kr.ac.pusan.pickle.gpu.dto.UpdateGpuPriorityRequest;
import kr.ac.pusan.pickle.gpu.dto.UpdateGpuStatusRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('SYS_MANAGER', 'SYS_ADMIN')")
public class AdminGpuController {
    private final GpuQueryService query;
    private final GpuMutationService mutations;
    private final GpuReviewService reviews;
    private final GpuReconciliationService reconciliation;
    public AdminGpuController(GpuQueryService query, GpuMutationService mutations, GpuReviewService reviews, GpuReconciliationService reconciliation) {
        this.query = query; this.mutations = mutations; this.reviews = reviews; this.reconciliation = reconciliation;
    }
    @GetMapping("/gpus") @Operation(summary = "관리자 GPU 목록")
    public List<GpuView> listAdminGpus() { return query.inventory(true); }
    @PatchMapping("/gpus/{gpuId}") @Operation(summary = "GPU 운영 상태 변경")
    public GpuView updateAdminGpu(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID gpuId,
            @Valid @RequestBody UpdateGpuStatusRequest request, HttpServletRequest http) { return mutations.updateGpu(actor, gpuId, request.status(), request.reason(), clientIp(http)); }
    @GetMapping("/gpu-allocations") @Operation(summary = "관리자 GPU 할당 목록")
    @PreAuthorize("hasAnyRole('ORG_VIEWER','ORG_MANAGER','ORG_ADMIN','SYS_VIEWER','SYS_MANAGER','SYS_ADMIN')")
    public PageResponse<GpuAllocationView> listAdminGpuAllocations(@AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) UUID orgId, @RequestParam(required = false) UUID workspaceId,
            @RequestParam(required = false) GpuAllocationStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return query.adminList(actor, orgId, workspaceId, status, page, size);
    }
    @GetMapping("/gpu-allocations/{allocationId}") @Operation(summary = "관리자 GPU 할당 상세")
    @PreAuthorize("hasAnyRole('ORG_VIEWER','ORG_MANAGER','ORG_ADMIN','SYS_VIEWER','SYS_MANAGER','SYS_ADMIN')")
    public GpuAllocationView getAdminGpuAllocation(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID allocationId) { return query.adminGet(actor, allocationId); }
    @PostMapping("/gpu-allocations/{allocationId}/priority") @Operation(summary = "GPU 대기 우선순위 변경")
    public GpuAllocationView updateGpuPriority(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID allocationId,
            @Valid @RequestBody UpdateGpuPriorityRequest request, HttpServletRequest http) { return mutations.priority(actor, allocationId, request.priority(), request.reason(), clientIp(http)); }
    @PostMapping("/gpu-allocations/{allocationId}/reclaim") @Operation(summary = "관리자 GPU 회수")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','SYS_MANAGER','SYS_ADMIN')")
    public GpuAllocationView reclaimGpu(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID allocationId,
            @Valid @RequestBody GpuReasonRequest request, HttpServletRequest http) { return mutations.reclaim(actor, allocationId, request.reason(), clientIp(http)); }
    @PostMapping("/gpu-allocations/{allocationId}/extend") @Operation(summary = "관리자 GPU 임대 연장")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','SYS_MANAGER','SYS_ADMIN')")
    public GpuAllocationView extendAdminGpuLease(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID allocationId,
            @Valid @RequestBody ExtendGpuLeaseRequest request, HttpServletRequest http) { return mutations.extend(actor, allocationId, request.hours(), request.reason(), clientIp(http), true); }
    @PostMapping("/gpu-allocations/{allocationId}/reconcile") @Operation(summary = "GPU 연결 상태 재확인", description = "전원과 장치 설정을 변경하지 않고 실제 상태가 확인된 작업의 잠금만 정리합니다. GPU 점유와 회수 의도는 유지합니다.")
    public GpuAllocationView reconcileGpu(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID allocationId,
            @Valid @RequestBody GpuReasonRequest request, HttpServletRequest http) { return reconciliation.reconcile(actor, allocationId, request.reason(), clientIp(http)); }
    @GetMapping("/gpu-reclaim-reviews") @Operation(summary = "GPU 회수 검토 목록")
    public PageResponse<GpuReclaimReviewView> listGpuReclaimReviews(@AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) { return reviews.list(actor, orgId, page, size); }
    @PostMapping("/gpu-reclaim-reviews/{reviewId}/decision") @Operation(summary = "GPU 회수 검토 결정")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decideGpuReclaimReview(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID reviewId,
            @Valid @RequestBody DecideGpuReviewRequest request, HttpServletRequest http) { reviews.decide(actor, reviewId, request.decision(), request.reason(), clientIp(http)); }
}
