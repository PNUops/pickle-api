package kr.ac.pusan.pickle.resource;

import java.util.List;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.resource.dto.ResourceSummaryResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.VmQueryService;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.dto.VmSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/** The VM's answers to {@link ResourceTypeAdapter}. */
@Component
public class VmResourceAdapter implements ResourceTypeAdapter {

    private final VmRepository vmRepository;
    private final VmQueryService vmQueryService;

    public VmResourceAdapter(VmRepository vmRepository, VmQueryService vmQueryService) {
        this.vmRepository = vmRepository;
        this.vmQueryService = vmQueryService;
    }

    @Override
    public ResourceType type() {
        return ResourceType.VM;
    }

    @Override
    public List<Long> idsOwnedByWorkspace(long workspaceId) {
        return vmRepository.findIdsByWorkspaceIdIn(List.of(workspaceId));
    }

    @Override
    public Page<ResourceSummaryResponse> page(AuthenticatedUser actor, Long workspaceId,
            Pageable pageable) {
        // Reuses the VM list rather than re-deriving visibility: the masking
        // rules live in one place, so the inventory cannot drift into showing
        // more than the VM list does.
        var page = vmQueryService.listPage(actor, workspaceId, pageable);
        return new PageImpl<>(page.getContent().stream().map(VmResourceAdapter::toSummary).toList(),
                pageable, page.getTotalElements());
    }

    private static ResourceSummaryResponse toSummary(VmSummaryResponse vm) {
        return new ResourceSummaryResponse(vm.id(), ResourceType.VM, vm.name(), vm.displayName(),
                vm.status().name(), vm.workspaceId(), vm.workspaceName(), vm.accessLimited(),
                vm.ownerNames(), vm.accessManageAllowed(), vm.createdAt());
    }
}
