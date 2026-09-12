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
import kr.ac.pusan.pickle.gpu.dto.AttachGpuRequest;
import kr.ac.pusan.pickle.gpu.dto.ConfirmGpuActionRequest;
import kr.ac.pusan.pickle.gpu.dto.ExtendGpuLeaseRequest;
import kr.ac.pusan.pickle.gpu.dto.GpuAllocationView;
import kr.ac.pusan.pickle.gpu.dto.GpuAttachmentOption;
import kr.ac.pusan.pickle.gpu.dto.GpuView;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class GpuController {
    private final GpuQueryService query;
    private final GpuMutationService mutations;
    public GpuController(GpuQueryService query, GpuMutationService mutations) { this.query = query; this.mutations = mutations; }
    @GetMapping("/gpus") @Operation(summary = "GPU 목록")
    public List<GpuView> listGpus() { return query.inventory(false); }
    @GetMapping("/gpu-allocations") @Operation(summary = "GPU 할당 목록")
    public PageResponse<GpuAllocationView> listGpuAllocations(@AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) UUID workspaceId, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) { return query.list(actor, workspaceId, page, size); }
    @GetMapping("/gpu-allocations/{allocationId}") @Operation(summary = "GPU 할당 상세")
    public GpuAllocationView getGpuAllocation(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID allocationId) { return query.get(actor, allocationId); }
    @GetMapping("/gpu-allocations/{allocationId}/attachment-options") @Operation(summary = "GPU에 연결할 가상머신")
    public List<GpuAttachmentOption> listGpuAttachmentOptions(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID allocationId) { return mutations.options(actor, allocationId); }
    @PostMapping("/gpu-allocations/{allocationId}/attach") @Operation(summary = "가상머신에 GPU 연결", description = "가상머신 중단에 동의한 뒤 연결합니다. 임대는 이미 시작되어 있으며 연결 실패로 초기화되지 않습니다.")
    public GpuAllocationView attachGpu(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID allocationId,
            @Valid @RequestBody AttachGpuRequest request, HttpServletRequest http) { return mutations.attach(actor, allocationId, request.vmId(), clientIp(http)); }
    @PostMapping("/gpu-allocations/{allocationId}/detach") @Operation(summary = "GPU 연결 해제", description = "연결만 해제하며 GPU 점유와 임대 기간은 유지합니다.")
    public GpuAllocationView detachGpu(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID allocationId,
            @Valid @RequestBody ConfirmGpuActionRequest request, HttpServletRequest http) { return mutations.detach(actor, allocationId, clientIp(http)); }
    @PostMapping("/gpu-allocations/{allocationId}/release") @Operation(summary = "GPU 반납")
    public GpuAllocationView releaseGpu(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID allocationId,
            @Valid @RequestBody ConfirmGpuActionRequest request, HttpServletRequest http) { return mutations.release(actor, allocationId, clientIp(http)); }
    @PostMapping("/gpu-allocations/{allocationId}/extend") @Operation(summary = "GPU 임대 연장")
    public GpuAllocationView extendGpuLease(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID allocationId,
            @Valid @RequestBody ExtendGpuLeaseRequest request, HttpServletRequest http) { return mutations.extend(actor, allocationId, request.hours(), request.reason(), clientIp(http), false); }
}
