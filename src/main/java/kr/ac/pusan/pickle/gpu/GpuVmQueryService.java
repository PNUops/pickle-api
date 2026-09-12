package kr.ac.pusan.pickle.gpu;

import kr.ac.pusan.pickle.access.ResourceAccessResolver;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.gpu.dto.GpuVmSummary;
import org.springframework.stereotype.Service;

@Service
public class GpuVmQueryService {
    private final GpuStore store;
    private final ResourceAccessResolver access;
    public GpuVmQueryService(GpuStore store, ResourceAccessResolver access) { this.store = store; this.access = access; }
    public GpuVmSummary summary(long vmId, Long actorId) {
        return store.all().stream().filter(a -> a.vmId() != null && a.vmId() == vmId && a.connectionStatus() != GpuConnectionStatus.NONE).findFirst()
                .map(a -> new GpuVmSummary(a.publicId(), a.name(), store.gpu(a.gpuId()).orElseThrow().model(), a.connectionStatus(),
                        actorId != null && access.standing(ResourceType.GPU, a.id(), a.workspaceId(), actorId).role() != null)).orElse(null);
    }
}
