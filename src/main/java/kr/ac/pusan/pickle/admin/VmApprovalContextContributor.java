package kr.ac.pusan.pickle.admin;

import java.util.List;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.Capacity;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.OrgHeadroom;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.Resources;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.VmContext;
import kr.ac.pusan.pickle.admin.dto.ResourceTotalsResponse;
import kr.ac.pusan.pickle.admin.dto.VmBriefResponse;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.springframework.stereotype.Component;

/** VM capacity and allocation panels for an approval decision. */
@Component
public class VmApprovalContextContributor implements ApprovalContextContributor {

    private final VmRepository vmRepository;
    private final OrgHeadroomService orgHeadroomService;

    public VmApprovalContextContributor(VmRepository vmRepository,
            OrgHeadroomService orgHeadroomService) {
        this.vmRepository = vmRepository;
        this.orgHeadroomService = orgHeadroomService;
    }

    @Override
    public ResourceType type() {
        return ResourceType.VM;
    }

    @Override
    public Contribution contribute(Request request, List<Long> applicantWorkspaceIds) {
        List<Vm> applicantVms = applicantWorkspaceIds.isEmpty()
                ? List.of()
                : vmRepository.findActiveByWorkspaceIdIn(applicantWorkspaceIds, VmStatus.DELETED);
        List<Vm> workspaceVms = vmRepository.findActiveByWorkspaceIdIn(
                List.of(request.getWorkspaceId()), VmStatus.DELETED);
        OrgHeadroomService.HeadroomResult headroom = orgHeadroomService.headroom(
                OrgScope.of(request.getOrgId()));
        return Contribution.vm(new VmContext(
                resources(applicantVms),
                resources(workspaceVms),
                new OrgHeadroom(headroom.allocated(),
                        new Capacity(headroom.capacityVcpu(), headroom.capacityMemoryMb(),
                                headroom.capacityDiskGb()),
                        headroom.vcpuRatio(), headroom.memoryRatio(), headroom.diskRatio(),
                        headroom.warnings()),
                headroom.guidance()));
    }

    private static Resources resources(List<Vm> vms) {
        return new Resources(vms.stream().map(VmBriefResponse::from).toList(),
                ResourceTotalsResponse.of(vms));
    }
}
