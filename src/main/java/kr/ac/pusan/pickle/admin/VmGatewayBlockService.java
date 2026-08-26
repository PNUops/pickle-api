package kr.ac.pusan.pickle.admin;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.VmGatewayBlockUpdateRequest;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmActorKind;
import kr.ac.pusan.pickle.vm.VmEvent;
import kr.ac.pusan.pickle.vm.VmEventRepository;
import kr.ac.pusan.pickle.vm.VmEventType;
import kr.ac.pusan.pickle.vm.VmQueryService;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract {@code PATCH /admin/vms/{vmId}/gateway-block}: per-VM kill switch
 * for SSH-gateway routing and web-terminal session creation (enforced by
 * SshGatewayRouteService / TerminalService reading the flag). Declarative and
 * state-free — any VM can be blocked or unblocked at any time; idempotent
 * re-application returns the detail without writing an event or audit row.
 * SYS_ADMIN-only (dangerous-op policy), so no org scoping applies.
 */
@Service
public class VmGatewayBlockService {

    private final VmRepository vmRepository;
    private final VmEventRepository vmEventRepository;
    private final VmQueryService vmQueryService;
    private final AuditService auditService;

    public VmGatewayBlockService(VmRepository vmRepository, VmEventRepository vmEventRepository,
            VmQueryService vmQueryService, AuditService auditService) {
        this.vmRepository = vmRepository;
        this.vmEventRepository = vmEventRepository;
        this.vmQueryService = vmQueryService;
        this.auditService = auditService;
    }

    @Transactional
    public kr.ac.pusan.pickle.vm.dto.VmDetailResponse updateBlock(AuthenticatedUser actor,
            UUID publicVmId, VmGatewayBlockUpdateRequest request, String ip) {
        Vm vm = vmRepository.findByPublicId(publicVmId).orElseThrow(VmGatewayBlockService::vmNotFound);
        long vmId = vm.getId();
        boolean blocked = request.blocked();
        // The repo update is CAS-style (flips only when the value differs), so
        // the rowcount — not a pre-read — decides whether this call is the
        // recording transition. Concurrent opposite toggles each record their
        // own flip instead of one being silently absorbed.
        if (vmRepository.updateSshGatewayBlocked(vmId, blocked, Instant.now()) == 1) {
            String label = blocked ? "차단" : "차단 해제";
            String detail = request.reason() == null || request.reason().isBlank()
                    ? "SSH·웹 터미널 " + label
                    : "SSH·웹 터미널 %s: %s".formatted(label, request.reason());
            vmEventRepository.save(new VmEvent(vmId,
                    blocked ? VmEventType.GATEWAY_BLOCK : VmEventType.GATEWAY_UNBLOCK,
                    actor.id(), VmActorKind.ADMIN, detail));
            Map<String, Object> auditDetail = new HashMap<>();
            auditDetail.put("blocked", blocked);
            if (request.reason() != null && !request.reason().isBlank()) {
                auditDetail.put("reason", request.reason());
            }
            auditService.recordAfterCommit(actor.id(), actor.role().name(),
                    blocked ? AuditService.VM_GATEWAY_BLOCK : AuditService.VM_GATEWAY_UNBLOCK,
                    "vm", vm.getPublicId(), auditDetail, ip);
        }
        // Viewer is an admin, not a grantee → myResourceRole null (period-update precedent).
        return vmQueryService.detailOf(vmRepository.findById(vmId).orElseThrow(), null);
    }

    private static ApiException vmNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 VM이 존재하지 않습니다.");
    }
}
