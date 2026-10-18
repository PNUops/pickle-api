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
    private final kr.ac.pusan.pickle.security.institution.ResourceInstitutionPermissions permissions;
    private final kr.ac.pusan.pickle.security.institution.InstitutionAuthorization authorization;

    public VmApprovalContextContributor(VmRepository vmRepository,
            OrgHeadroomService orgHeadroomService, OrgRepository orgs,
            VmRequestDetailRepository details, OsImageRepository images, OsImageCatalogService catalog,
            kr.ac.pusan.pickle.security.institution.ResourceInstitutionPermissions permissions,
            kr.ac.pusan.pickle.security.institution.InstitutionAuthorization authorization) {
        this.vmRepository = vmRepository;
        this.orgHeadroomService = orgHeadroomService;
        this.orgs = orgs;
        this.details = details;
        this.images = images;
        this.catalog = catalog; this.permissions = permissions; this.authorization = authorization;
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
        boolean headroomAllowed = metadataAllForInstitution(actor, request.getOrgId());
        OrgHeadroomService.HeadroomResult headroom = headroomAllowed
                ? orgHeadroomService.headroom(OrgScope.of(request.getOrgId())) : null;
        Map<Long, Org> resourceOrgs = orgs.findAllById(java.util.stream.Stream.concat(applicantVms.stream(), workspaceVms.stream())
                .map(Vm::getOrgId).filter(java.util.Objects::nonNull).distinct().toList()).stream().collect(Collectors.toMap(Org::getId, Function.identity()));
        boolean requestReference = request.getResourceType() == ResourceType.VM
                && java.util.stream.Stream.of(kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_REQUEST_READ,
                        kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_REQUEST_SUPPORT_READ,
                        kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_REQUEST_DECIDE,
                        kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_ROSTER_ISSUE)
                    .anyMatch(work -> readWork(actor, work, request.getOrgId(), request.getWorkspaceId()));
        AdminApprovalImageReference requestedImage = !requestReference ? null
                : details.findById(request.getId()).flatMap(detail -> images.findById(detail.getImageId()))
                        .map(image -> new AdminApprovalImageReference(image.getPublicId(), image.getDisplayName(), image.getVersion(),
                                currentlySelectable(image.getPublicId()))).orElse(null);
        return Contribution.vm(new VmContext(
                resources(applicantVms, resourceOrgs, request, actor),
                resources(workspaceVms, resourceOrgs, request, actor),
                headroom == null ? null : new OrgHeadroom(headroom.allocated(),
                        new Capacity(headroom.capacityVcpu(), headroom.capacityMemoryMb(),
                                headroom.capacityDiskGb()),
                        headroom.vcpuRatio(), headroom.memoryRatio(), headroom.diskRatio(),
                        headroom.warnings()),
                headroom == null ? null : headroom.guidance(), requestedImage));
    }

    private Resources resources(List<Vm> vms, Map<Long, Org> orgs, Request request, AuthenticatedUser actor) {
        boolean metadataSource = actor.role().isSysTier()
                || !authorization.sources(actor, kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_METADATA_READ).isEmpty()
                || !authorization.sources(actor, kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_METADATA_SUPPORT_READ).isEmpty();
        boolean decisionReference = request.getResourceType() == ResourceType.VM
                && (readWork(actor, kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_REQUEST_DECIDE,
                        request.getOrgId(), request.getWorkspaceId())
                    || readWork(actor, kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_ROSTER_ISSUE,
                        request.getOrgId(), request.getWorkspaceId()));
        if (!metadataSource && !decisionReference) { return new Resources(null, null); }
        List<Vm> metadataRows = vms.stream().filter(vm -> metadata(actor, vm)).toList();
        List<VmBriefResponse> rows = vms.stream().filter(vm -> metadataRows.contains(vm)
                || decisionReference && java.util.Objects.equals(vm.getOrgId(), request.getOrgId())
                    && java.util.Objects.equals(vm.getWorkspaceId(), request.getWorkspaceId()))
                .map(vm -> metadataRows.contains(vm)
                        ? VmBriefResponse.from(vm, orgs.get(vm.getOrgId()) == null ? null : orgs.get(vm.getOrgId()).getPublicId(), true)
                        : new VmBriefResponse(vm.getPublicId(), vm.getName(), vm.getStatus(),
                                null, null, null, null, null, false)).toList();
        return new Resources(rows, metadataSource ? ResourceTotalsResponse.of(metadataRows) : null);
    }

    private boolean metadata(AuthenticatedUser actor, Vm vm) {
        return readWork(actor, kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_METADATA_READ,
                vm.getOrgId(), vm.getWorkspaceId())
                || readWork(actor, kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_METADATA_SUPPORT_READ,
                        vm.getOrgId(), vm.getWorkspaceId());
    }

    private boolean readWork(AuthenticatedUser actor, kr.ac.pusan.pickle.security.institution.InstitutionWork work,
            Long institution, long workspace) {
        return permissions.resource(actor, work, institution, workspace, true,
                kr.ac.pusan.pickle.user.UserRole.SYS_VIEWER, kr.ac.pusan.pickle.user.UserRole.SYS_MANAGER,
                kr.ac.pusan.pickle.user.UserRole.SYS_ADMIN);
    }

    private boolean metadataAllForInstitution(AuthenticatedUser actor, Long institution) {
        if (actor.role().isSysTier()) { return true; }
        java.util.UUID target = institution == null ? null : orgs.findById(institution).map(Org::getPublicId).orElse(null);
        if (target == null) { return false; }
        return java.util.stream.Stream.of(kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_METADATA_READ,
                kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_METADATA_SUPPORT_READ)
                .flatMap(work -> authorization.sources(actor, work).stream())
                .anyMatch(source -> source.workspaceScope() != null
                        && source.workspaceScope().mode() == kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope.Mode.ALL
                        && (source.requiredInstitutionId() == null || target.equals(source.requiredInstitutionId())));
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
