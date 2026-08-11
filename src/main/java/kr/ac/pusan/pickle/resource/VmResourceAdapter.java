package kr.ac.pusan.pickle.resource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.access.ResourceAccessAudit;
import kr.ac.pusan.pickle.access.ResourceAccessMessages;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.resource.dto.ResourceSummaryResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.VmQueryService;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.vm.dto.VmSummaryResponse;
import kr.ac.pusan.pickle.vmsettings.VmSettingsService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/** The VM's answers to {@link ResourceTypeAdapter}. */
@Component
public class VmResourceAdapter implements ResourceTypeAdapter {

    /**
     * Every sentence the access machinery says about a VM. Public because the
     * VM's own services refuse in the same words, and one wording that two
     * places copy is one wording that drifts.
     */
    public static final ResourceAccessMessages MESSAGES = new ResourceAccessMessages(
            "해당 VM이 존재하지 않습니다.",
            new ResourceAccessMessages.Refusal("이 VM에 접근할 권한이 없습니다",
                    "이 VM의 접근 목록에 등록되어 있지 않습니다. 자원 소유자에게 접근 권한을 요청해 주세요."),
            new ResourceAccessMessages.Refusal("접근 권한을 관리할 권한이 없습니다",
                    "이 VM의 소유자 또는 워크스페이스 소유자만 접근 권한을 관리할 수 있습니다."),
            "이 VM을 소유한 워크스페이스의 구성원만 접근 권한을 받을 수 있습니다. 먼저 워크스페이스에 추가해 주세요.",
            ErrorCodes.VM_ACCESS_GRANT_EXISTS,
            new ResourceAccessMessages.Refusal("이미 접근 권한이 있습니다",
                    "이 대상은 이미 이 VM의 접근 목록에 있습니다. 등급을 바꾸려면 기존 항목을 수정해 주세요."));

    private static final ResourceAccessAudit AUDIT = new ResourceAccessAudit("vm",
            AuditService.VM_ACCESS_GRANT_ADD, AuditService.VM_ACCESS_GRANT_UPDATE,
            AuditService.VM_ACCESS_GRANT_REMOVE, AuditService.VM_ACCESS_BREAK_GLASS);

    private final VmRepository vmRepository;
    private final VmQueryService vmQueryService;
    private final VmSettingsService vmSettingsService;

    public VmResourceAdapter(VmRepository vmRepository, VmQueryService vmQueryService,
            VmSettingsService vmSettingsService) {
        this.vmRepository = vmRepository;
        this.vmQueryService = vmQueryService;
        this.vmSettingsService = vmSettingsService;
    }

    @Override
    public ResourceType type() {
        return ResourceType.VM;
    }

    @Override
    public Optional<ResourceIdentity> identify(long resourceId) {
        // No filter on status: a destroyed VM keeps its row and its access
        // list, which is what lets the people who used it still read its
        // history.
        return vmRepository.findById(resourceId).map(this::identityOf);
    }

    @Override
    public Optional<ResourceIdentity> identifyByPublicId(UUID publicId) {
        return vmRepository.findByPublicId(publicId).map(this::identityOf);
    }

    private ResourceIdentity identityOf(Vm vm) {
        return new ResourceIdentity(vm.getId(), vm.getPublicId(), vm.getWorkspaceId(), vm.getName(),
                vmSettingsService.string(vm.getId(), VmSettingsService.DISPLAY_NAME),
                vm.getStatus().name());
    }

    @Override
    public ResourceAccessMessages accessMessages() {
        return MESSAGES;
    }

    @Override
    public ResourceAccessAudit accessAudit() {
        return AUDIT;
    }

    @Override
    public List<Long> idsOwnedByWorkspace(long workspaceId) {
        return vmRepository.findIdsByWorkspaceIdIn(List.of(workspaceId));
    }

    @Override
    public long countLiveInWorkspace(long workspaceId) {
        // DELETING counts: the VM is still there until its destruction finishes.
        return vmRepository.countActiveByWorkspaceId(workspaceId, VmStatus.DELETED);
    }

    @Override
    public InventoryHead inventoryHead(AuthenticatedUser actor, UUID workspaceId, int limit) {
        // Reuses the VM list rather than re-deriving visibility: the masking
        // rules live in one place, so the inventory cannot drift into showing
        // more than the VM list does. The count rides the same call for the
        // same reason.
        //
        // The sort keys are named here and nowhere else. The inventory asks for
        // "newest first, ties in this type's own order" and this is where that
        // meaning becomes `createdAt` and `id` — property names of the Vm
        // entity, which no other type should have to adopt.
        var page = vmQueryService.listPage(actor, workspaceId,
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt")
                        .and(Sort.by(Sort.Direction.DESC, "id"))));
        return new InventoryHead(
                page.getContent().stream().map(VmResourceAdapter::toSummary).toList(),
                page.getTotalElements());
    }

    private static ResourceSummaryResponse toSummary(VmSummaryResponse vm) {
        return new ResourceSummaryResponse(vm.id(), ResourceType.VM, vm.name(), vm.displayName(),
                vm.status().name(), vm.workspaceId(), vm.workspaceName(), vm.accessLimited(),
                vm.ownerNames(), vm.accessManageAllowed(), vm.createdAt());
    }
}
