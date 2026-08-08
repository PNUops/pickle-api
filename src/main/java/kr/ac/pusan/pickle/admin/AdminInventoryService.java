package kr.ac.pusan.pickle.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.ac.pusan.pickle.admin.dto.AdminOsImageResponse;
import kr.ac.pusan.pickle.admin.dto.CreateVmFlavorRequest;
import kr.ac.pusan.pickle.admin.dto.NodeSummaryResponse;
import kr.ac.pusan.pickle.admin.dto.UpdateNodeStatusRequest;
import kr.ac.pusan.pickle.admin.dto.UpdateOsImageStatusRequest;
import kr.ac.pusan.pickle.admin.dto.UpdateVmFlavorRequest;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import kr.ac.pusan.pickle.inventory.VmFlavor;
import kr.ac.pusan.pickle.inventory.VmFlavorRepository;
import kr.ac.pusan.pickle.inventory.OsImage;
import kr.ac.pusan.pickle.inventory.OsImageRepository;
import kr.ac.pusan.pickle.inventory.dto.VmFlavorResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operational-state write paths for the inventory (contract v0.21.0):
 * OS image ACTIVE/DISABLED toggle (old-revision retirement) and node
 * ACTIVE/MAINTENANCE/OFFLINE transitions (placement only picks ACTIVE, so a
 * transition alone realises drain-from-placement). Deliberately no
 * last-ACTIVE guard: retiring the final OS image or closing the last node is
 * a legitimate "close provisioning" act, and the downstream failure modes are
 * graceful (request submit → validation reject, placement → parked task); the
 * console computes the warning instead. Idempotent re-application returns the
 * resource without an audit row.
 */
@Service
public class AdminInventoryService {

    private final OsImageRepository osImageRepository;
    private final VmFlavorRepository vmFlavorRepository;
    private final NodeRepository nodeRepository;
    private final AdminNodeQueryService adminNodeQueryService;
    private final AuditService auditService;

    public AdminInventoryService(OsImageRepository osImageRepository,
            VmFlavorRepository vmFlavorRepository,
            NodeRepository nodeRepository, AdminNodeQueryService adminNodeQueryService,
            AuditService auditService) {
        this.osImageRepository = osImageRepository;
        this.vmFlavorRepository = vmFlavorRepository;
        this.nodeRepository = nodeRepository;
        this.adminNodeQueryService = adminNodeQueryService;
        this.auditService = auditService;
    }

    /** Contract {@code listAdminOsImages}: every OS image, retired revisions included. */
    @Transactional(readOnly = true)
    public List<AdminOsImageResponse> listOsImages() {
        return osImageRepository.findAll(Sort.by("id")).stream()
                .map(AdminOsImageResponse::from)
                .toList();
    }

    @Transactional
    public AdminOsImageResponse updateCatalogStatus(AuthenticatedUser actor, long imageId,
            UpdateOsImageStatusRequest request, String ip) {
        OsImage image = osImageRepository.findById(imageId)
                .orElseThrow(() -> notFound("해당 템플릿이 존재하지 않습니다."));
        if (image.getStatus() != request.status()) {
            String fromStatus = image.getStatus().name();
            image.setStatus(request.status());
            auditService.recordAfterCommit(actor.id(), actor.role().name(),
                    AuditService.OS_IMAGE_STATUS_UPDATE, "template", imageId,
                    Map.of("name", image.getName(), "version", image.getVersion(),
                            "fromStatus", fromStatus, "toStatus", request.status().name()), ip);
        }
        return AdminOsImageResponse.from(image);
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

    /** Contract {@code listAdminVmFlavors}: every preset, retired ones included. */
    @Transactional(readOnly = true)
    public List<VmFlavorResponse> listFlavors() {
        return vmFlavorRepository.findAll(Sort.by("id")).stream()
                .map(VmFlavorResponse::from)
                .toList();
    }

    @Transactional
    public VmFlavorResponse createFlavor(AuthenticatedUser actor, CreateVmFlavorRequest request,
            String ip) {
        if (vmFlavorRepository.existsByName(request.name())) {
            throw nameTaken();
        }
        // saveAndFlush + catch: the existsByName above is a pre-check only —
        // under a concurrent create of the same name the unique constraint is
        // the arbiter, and the loser must get the same 422 field error instead
        // of a 500 at commit time.
        VmFlavor flavor;
        try {
            flavor = vmFlavorRepository.saveAndFlush(new VmFlavor(request.name(),
                    request.displayName(), request.vcpu(), request.memoryMb(), request.diskGb(),
                    CatalogStatus.ACTIVE, Texts.blankToNull(request.notes())));
        } catch (DataIntegrityViolationException raced) {
            throw nameTaken();
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.FLAVOR_CREATE,
                "vm_flavor", flavor.getId(),
                Map.of("name", flavor.getName(), "vcpu", flavor.getVcpu(),
                        "memoryMb", flavor.getMemoryMb(), "diskGb", flavor.getDiskGb()), ip);
        return VmFlavorResponse.from(flavor);
    }

    /**
     * Partial edit; audits only real changes (old→new per field) and answers
     * idempotent re-application without an audit row, like the status toggles.
     */
    @Transactional
    public VmFlavorResponse updateFlavor(AuthenticatedUser actor, long flavorId,
            UpdateVmFlavorRequest request, String ip) {
        VmFlavor flavor = vmFlavorRepository.findById(flavorId)
                .orElseThrow(() -> notFound("해당 사양 프리셋이 존재하지 않습니다."));
        if (request.displayName() == null && request.vcpu() == null && request.memoryMb() == null
                && request.diskGb() == null && request.notes() == null && request.status() == null) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("displayName",
                    "변경할 필드를 최소 1개 지정해야 합니다.")));
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        if (request.displayName() != null && !request.displayName().equals(flavor.getDisplayName())) {
            changes.put("displayName", flavor.getDisplayName() + " -> " + request.displayName());
            flavor.setDisplayName(request.displayName());
        }
        if (request.vcpu() != null && request.vcpu() != flavor.getVcpu()) {
            changes.put("vcpu", flavor.getVcpu() + " -> " + request.vcpu());
            flavor.setVcpu(request.vcpu());
        }
        if (request.memoryMb() != null && request.memoryMb() != flavor.getMemoryMb()) {
            changes.put("memoryMb", flavor.getMemoryMb() + " -> " + request.memoryMb());
            flavor.setMemoryMb(request.memoryMb());
        }
        if (request.diskGb() != null && request.diskGb() != flavor.getDiskGb()) {
            changes.put("diskGb", flavor.getDiskGb() + " -> " + request.diskGb());
            flavor.setDiskGb(request.diskGb());
        }
        // Compare the value that would actually be stored: notes are persisted
        // blank-to-null, so "" against an already-null column (or a
        // whitespace-only re-send) is a no-op, not an audited change.
        String notes = request.notes() != null ? Texts.blankToNull(request.notes()) : null;
        if (request.notes() != null && !Objects.equals(notes, flavor.getNotes())) {
            changes.put("notes", "updated");
            flavor.setNotes(notes);
        }
        if (request.status() != null && request.status() != flavor.getStatus()) {
            changes.put("status", flavor.getStatus().name() + " -> " + request.status().name());
            flavor.setStatus(request.status());
        }
        if (!changes.isEmpty()) {
            changes.put("name", flavor.getName());
            auditService.recordAfterCommit(actor.id(), actor.role().name(),
                    AuditService.FLAVOR_UPDATE, "vm_flavor", flavorId, changes, ip);
        }
        return VmFlavorResponse.from(flavor);
    }

    private static ApiException nameTaken() {
        return ApiException.validationFailed(List.of(new FieldValidationError("name",
                "이미 존재하는 프리셋 이름입니다.")));
    }

    private static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", detail);
    }
}
