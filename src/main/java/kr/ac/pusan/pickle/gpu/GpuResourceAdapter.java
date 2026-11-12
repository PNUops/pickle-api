package kr.ac.pusan.pickle.gpu;

import kr.ac.pusan.pickle.common.error.ErrorCodes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceAccessAudit;
import kr.ac.pusan.pickle.access.ResourceAccessMessages;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.resource.ResourceIdentity;
import kr.ac.pusan.pickle.resource.ResourceTypeAdapter;
import kr.ac.pusan.pickle.resource.dto.ResourceSummaryResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.stereotype.Component;

@Component
public class GpuResourceAdapter implements ResourceTypeAdapter {
    public static final ResourceAccessMessages MESSAGES = new ResourceAccessMessages(
            "GPU 할당을 찾을 수 없어요.",
            new ResourceAccessMessages.Refusal("접근 권한이 없어요", "GPU 할당 소유자나 워크스페이스 소유자에게 접근 권한을 요청해 주세요."),
            new ResourceAccessMessages.Refusal("접근 권한을 관리할 수 없어요", "GPU 할당 소유자나 워크스페이스 소유자만 접근 권한을 관리할 수 있어요."),
            "같은 워크스페이스 구성원에게만 접근 권한을 줄 수 있어요.", ErrorCodes.GPU_ACCESS_GRANT_EXISTS,
            new ResourceAccessMessages.Refusal("이미 접근 권한이 있어요", "기존 접근 목록에서 등급을 바꿔 주세요."));
    private final GpuStore store;
    private final GpuQueryService query;
    public GpuResourceAdapter(GpuStore store, GpuQueryService query) { this.store = store; this.query = query; }
    @Override public ResourceType type() { return ResourceType.GPU; }
    @Override public Optional<ResourceIdentity> identify(long id) { return store.allocation(id).map(GpuResourceAdapter::identity); }
    @Override public Optional<ResourceIdentity> identifyByPublicId(UUID id) { return store.allocation(id).map(GpuResourceAdapter::identity); }
    @Override public ResourceAccessMessages accessMessages() { return MESSAGES; }
    @Override public ResourceAccessAudit accessAudit() {
        return new ResourceAccessAudit("gpu_allocation", "gpu.access.add", "gpu.access.update", "gpu.access.remove", "gpu.access.break_glass");
    }
    @Override public List<Long> idsOwnedByWorkspace(long id) {
        return store.all().stream().filter(a -> a.workspaceId() == id).map(GpuAllocation::id).toList();
    }
    @Override public long countLiveInWorkspace(long id) {
        return store.all().stream().filter(a -> a.workspaceId() == id)
                .filter(a -> a.status() != GpuAllocationStatus.RELEASED && a.status() != GpuAllocationStatus.CANCELED).count();
    }
    @Override public InventoryHead inventoryHead(AuthenticatedUser actor, UUID workspaceId, int limit) {
        var page = query.list(actor, workspaceId, 0, limit);
        return new InventoryHead(page.content().stream().map(a -> new ResourceSummaryResponse(a.id(), ResourceType.GPU,
                a.name(), null, a.status().name(), a.workspaceId(), a.workspaceName(), a.accessLimited(),
                a.ownerNames(), a.accessManageAllowed(), a.createdAt())).toList(), page.totalElements());
    }
    private static ResourceIdentity identity(GpuAllocation a) {
        return new ResourceIdentity(a.id(), a.publicId(), a.workspaceId(), a.name(), null, a.status().name());
    }
}
