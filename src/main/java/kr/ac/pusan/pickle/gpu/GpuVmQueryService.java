package kr.ac.pusan.pickle.gpu;

import kr.ac.pusan.pickle.access.ResourceAccessResolver;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.gpu.dto.GpuVmSummary;
import org.springframework.stereotype.Service;

@Service
public class GpuVmQueryService {
    private final GpuStore store;
    private final ResourceAccessResolver access;
    private final kr.ac.pusan.pickle.security.institution.ResourceInstitutionPermissions permissions;
    public GpuVmQueryService(GpuStore store, ResourceAccessResolver access,
            kr.ac.pusan.pickle.security.institution.ResourceInstitutionPermissions permissions) {
        this.store = store; this.access = access; this.permissions = permissions;
    }
    /** Connected hardware is a VM reference; opening GPU detail keeps its independent authority. */
    public GpuVmSummary adminSummary(long vmId, kr.ac.pusan.pickle.security.AuthenticatedUser actor) {
        return store.all().stream().filter(a -> a.vmId() != null && a.vmId() == vmId && a.connectionStatus() != GpuConnectionStatus.NONE)
                .findFirst().map(a -> {
                    boolean detail = access.standing(ResourceType.GPU, a.id(), a.workspaceId(), actor.id()).role() != null
                            || java.util.stream.Stream.of(kr.ac.pusan.pickle.security.institution.InstitutionWork.GPU_METADATA_READ,
                                    kr.ac.pusan.pickle.security.institution.InstitutionWork.GPU_METADATA_SUPPORT_READ)
                                .anyMatch(work -> permissions.resource(actor, work, a.orgId(), a.workspaceId(), true,
                                        kr.ac.pusan.pickle.user.UserRole.SYS_VIEWER, kr.ac.pusan.pickle.user.UserRole.SYS_MANAGER,
                                        kr.ac.pusan.pickle.user.UserRole.SYS_ADMIN));
                    return new GpuVmSummary(a.publicId(), a.name(), store.gpu(a.gpuId()).orElseThrow().model(), a.connectionStatus(), detail);
                }).orElse(null);
    }
    public GpuVmSummary summary(long vmId, Long actorId) {
        return store.all().stream().filter(a -> a.vmId() != null && a.vmId() == vmId && a.connectionStatus() != GpuConnectionStatus.NONE).findFirst()
                .map(a -> new GpuVmSummary(a.publicId(), a.name(), store.gpu(a.gpuId()).orElseThrow().model(), a.connectionStatus(),
                        actorId != null && access.standing(ResourceType.GPU, a.id(), a.workspaceId(), actorId).role() != null)).orElse(null);
    }
}
