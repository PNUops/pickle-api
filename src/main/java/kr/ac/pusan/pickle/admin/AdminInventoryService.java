package kr.ac.pusan.pickle.admin;

import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.admin.dto.AdminTemplateResponse;
import kr.ac.pusan.pickle.admin.dto.NodeSummaryResponse;
import kr.ac.pusan.pickle.admin.dto.UpdateNodeStatusRequest;
import kr.ac.pusan.pickle.admin.dto.UpdateTemplateStatusRequest;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.VmTemplate;
import kr.ac.pusan.pickle.inventory.VmTemplateRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operational-state write paths for the inventory (contract v0.21.0):
 * template ACTIVE/DISABLED toggle (old-revision retirement) and node
 * ACTIVE/MAINTENANCE/OFFLINE transitions (placement only picks ACTIVE, so a
 * transition alone realises drain-from-placement). Deliberately no
 * last-ACTIVE guard: retiring the final template or closing the last node is
 * a legitimate "close provisioning" act, and the downstream failure modes are
 * graceful (request submit → validation reject, placement → parked task); the
 * console computes the warning instead. Idempotent re-application returns the
 * resource without an audit row.
 */
@Service
public class AdminInventoryService {

    private final VmTemplateRepository vmTemplateRepository;
    private final NodeRepository nodeRepository;
    private final AdminNodeQueryService adminNodeQueryService;
    private final AuditService auditService;

    public AdminInventoryService(VmTemplateRepository vmTemplateRepository,
            NodeRepository nodeRepository, AdminNodeQueryService adminNodeQueryService,
            AuditService auditService) {
        this.vmTemplateRepository = vmTemplateRepository;
        this.nodeRepository = nodeRepository;
        this.adminNodeQueryService = adminNodeQueryService;
        this.auditService = auditService;
    }

    /** Contract {@code listAdminTemplates}: every template, retired revisions included. */
    @Transactional(readOnly = true)
    public List<AdminTemplateResponse> listTemplates() {
        return vmTemplateRepository.findAll(Sort.by("id")).stream()
                .map(AdminTemplateResponse::from)
                .toList();
    }

    @Transactional
    public AdminTemplateResponse updateTemplateStatus(AuthenticatedUser actor, long templateId,
            UpdateTemplateStatusRequest request, String ip) {
        VmTemplate template = vmTemplateRepository.findById(templateId)
                .orElseThrow(() -> notFound("해당 템플릿이 존재하지 않습니다."));
        if (template.getStatus() != request.status()) {
            String fromStatus = template.getStatus().name();
            template.setStatus(request.status());
            auditService.recordAfterCommit(actor.id(), actor.role().name(),
                    AuditService.TEMPLATE_STATUS_UPDATE, "template", templateId,
                    Map.of("name", template.getName(), "version", template.getVersion(),
                            "fromStatus", fromStatus, "toStatus", request.status().name()), ip);
        }
        return AdminTemplateResponse.from(template);
    }

    @Transactional
    public NodeSummaryResponse updateNodeStatus(AuthenticatedUser actor, long nodeId,
            UpdateNodeStatusRequest request, String ip) {
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> notFound("해당 노드가 존재하지 않습니다."));
        if (node.getStatus() != request.status()) {
            String fromStatus = node.getStatus().name();
            node.setStatus(request.status());
            auditService.recordAfterCommit(actor.id(), actor.role().name(),
                    AuditService.NODE_STATUS_UPDATE, "node", nodeId,
                    Map.of("name", node.getName(), "fromStatus", fromStatus,
                            "toStatus", request.status().name()), ip);
        }
        return adminNodeQueryService.getNode(nodeId);
    }

    private static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", detail);
    }
}
