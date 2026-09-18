package kr.ac.pusan.pickle.networkpolicy;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import kr.ac.pusan.pickle.networkpolicy.dto.UpdateVmNetworkPolicyRequest;
import kr.ac.pusan.pickle.networkpolicy.dto.VmNetworkPolicyView;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Organisation/system-scoped intervention on one VM policy. */
@RestController
@RequestMapping("/api/v1/admin/vms/{vmId}/network-policy")
public class AdminVmNetworkPolicyController {

    private final VmNetworkPolicyService service;

    public AdminVmNetworkPolicyController(VmNetworkPolicyService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ORG_VIEWER','ORG_MANAGER','ORG_ADMIN','SYS_VIEWER','SYS_MANAGER','SYS_ADMIN')")
    @Operation(operationId = "getAdminVmNetworkPolicy", summary = "관리자 VM 통신 정책 조회")
    public VmNetworkPolicyView get(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID vmId) {
        return service.adminView(actor, vmId);
    }

    @PutMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ORG_MANAGER','ORG_ADMIN','SYS_MANAGER','SYS_ADMIN')")
    @Operation(operationId = "updateAdminVmNetworkPolicy", summary = "관리자 VM 통신 정책 변경")
    public VmNetworkPolicyView update(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID vmId,
            @Valid @RequestBody UpdateVmNetworkPolicyRequest request,
            HttpServletRequest httpRequest) {
        return service.adminUpdate(actor, vmId, request, clientIp(httpRequest));
    }
}
