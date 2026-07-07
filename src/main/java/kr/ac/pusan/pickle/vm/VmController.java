package kr.ac.pusan.pickle.vm;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.dto.VmDetailResponse;
import kr.ac.pusan.pickle.vm.dto.VmSummaryResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code vms}, read-only in M2 (openapi.yaml v0.2.3, server /api/v1). */
@RestController
@RequestMapping("/api/v1/vms")
public class VmController {

    private final VmQueryService vmQueryService;

    public VmController(VmQueryService vmQueryService) {
        this.vmQueryService = vmQueryService;
    }

    @GetMapping
    public PageResponse<VmSummaryResponse> listVms(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) Long groupId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return vmQueryService.list(principal, groupId, page, size);
    }

    @GetMapping("/{vmId}")
    public VmDetailResponse getVm(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long vmId) {
        return vmQueryService.get(principal, vmId);
    }
}
