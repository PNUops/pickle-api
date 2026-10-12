package kr.ac.pusan.pickle.admin;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.Capacity;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.OrgHeadroom;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.Resources;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.VmContext;
import kr.ac.pusan.pickle.admin.dto.ResourceTotalsResponse;
import kr.ac.pusan.pickle.admin.dto.VmBriefResponse;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.inventory.OsImageCatalogService;
import kr.ac.pusan.pickle.inventory.OsImageRepository;
import kr.ac.pusan.pickle.request.vm.VmRequestDetailRepository;
import kr.ac.pusan.pickle.admin.dto.AdminApprovalImageReference;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.springframework.stereotype.Component;

/** VM capacity and allocation panels for an approval decision. */
@Component
public class VmApprovalContextContributor implements ApprovalContextContributor {

    private final VmRepository vmRepository;
    private final OrgHeadroomService orgHeadroomService;
    private final OrgRepository orgs;
    private final VmRequestDetailRepository details;
    private final OsImageRepository images;
    private final OsImageCatalogService catalog;

    public VmApprovalContextContributor(VmRepository vmRepository,
            OrgHeadroomService orgHeadroomService, OrgRepository orgs,
            VmRequestDetailRepository details, OsImageRepository images, OsImageCatalogService catalog) {
        this.vmRepository = vmRepository;
        this.orgHeadroomService = orgHeadroomService;
        this.orgs = orgs;
        this.details = details;
        this.images = images;
        this.catalog = catalog;
    }

    @Override
    public ResourceType type() {
        return ResourceType.VM;
    }

    @Override
    public Contribution contribute(Request request, List<Long> applicantWorkspaceIds, AuthenticatedUser actor) {
        List<Vm> applicantVms = applicantWorkspaceIds.isEmpty()
                ? List.of()
                : vmRepository.findActiveByWorkspaceIdIn(applicantWorkspaceIds, VmStatus.DELETED);
        List<Vm> workspaceVms = vmRepository.findActiveByWorkspaceIdIn(
                List.of(request.getWorkspaceId()), VmStatus.DELETED);
        OrgHeadroomService.HeadroomResult headroom = orgHeadroomService.headroom(
                OrgScope.of(request.getOrgId()));
        Map<Long, Org> resourceOrgs = orgs.findAllById(java.util.stream.Stream.concat(applicantVms.stream(), workspaceVms.stream())
                .map(Vm::getOrgId).distinct().toList()).stream().collect(Collectors.toMap(Org::getId, Function.identity()));
        AdminApprovalImageReference requestedImage = request.getResourceType() != ResourceType.VM ? null
                : details.findById(request.getId()).flatMap(detail -> images.findById(detail.getImageId()))
                        .map(image -> new AdminApprovalImageReference(image.getPublicId(), image.getDisplayName(), image.getVersion(),
                                currentlySelectable(image.getPublicId()))).orElse(null);
        return Contribution.vm(new VmContext(
                resources(applicantVms, resourceOrgs, actor),
                resources(workspaceVms, resourceOrgs, actor),
                new OrgHeadroom(headroom.allocated(),
                        new Capacity(headroom.capacityVcpu(), headroom.capacityMemoryMb(),
                                headroom.capacityDiskGb()),
                        headroom.vcpuRatio(), headroom.memoryRatio(), headroom.diskRatio(),
                        headroom.warnings()),
                headroom.guidance(), requestedImage));
    }

    private static Resources resources(List<Vm> vms, Map<Long, Org> orgs, AuthenticatedUser actor) {
        return new Resources(vms.stream().map(vm -> VmBriefResponse.from(vm, orgs.get(vm.getOrgId()).getPublicId(),
                        !actor.role().isOrgTier() || actor.reads(vm.getOrgId()))).toList(),
                ResourceTotalsResponse.of(vms));
    }

    private boolean currentlySelectable(java.util.UUID publicId) {
        try {
            catalog.requireSelectable(publicId);
            return true;
        } catch (OsImageCatalogService.UnknownImageException | OsImageCatalogService.UnavailableImageException unavailable) {
            return false;
        }
    }
}
