package kr.ac.pusan.pickle.gpu;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceAccessResolver;
import kr.ac.pusan.pickle.access.ResourceStanding;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.gpu.dto.GpuAllocationView;
import kr.ac.pusan.pickle.gpu.dto.GpuView;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class GpuQueryService {
    private final GpuStore store;
    private final ResourceAccessResolver access;
    private final WorkspaceMemberRepository members;
    private final WorkspaceRepository workspaces;
    private final OrgRepository orgs;
    private final VmRepository vms;
    private final NodeRepository nodes;

    public GpuQueryService(GpuStore store, ResourceAccessResolver access, WorkspaceMemberRepository members,
            WorkspaceRepository workspaces, OrgRepository orgs, VmRepository vms, NodeRepository nodes) {
        this.store = store; this.access = access; this.members = members; this.workspaces = workspaces;
        this.orgs = orgs; this.vms = vms; this.nodes = nodes;
    }

    public List<GpuView> inventory(boolean admin) {
        return store.gpus().stream().filter(g -> admin || g.status() == GpuStatus.ACTIVE).map(this::gpuView).toList();
    }

    public GpuView gpuView(Gpu gpu) {
        var node = nodes.findById(gpu.nodeId()).orElseThrow();
        return new GpuView(gpu.publicId(), node.getPublicId(), gpu.model(), gpu.vramMb(), gpu.status(),
                gpu.status() == GpuStatus.ACTIVE && node.getStatus() == kr.ac.pusan.pickle.inventory.NodeStatus.ACTIVE
                        && store.available(gpu.id()));
    }

    public PageResponse<GpuAllocationView> list(AuthenticatedUser actor, UUID workspaceId, int page, int size) {
        var workspaceIds = members.findWithWorkspaceByUserId(actor.id()).stream().map(m -> m.getWorkspace().getId()).toList();
        Long selected = workspaceId == null ? null : workspaces.findByPublicId(workspaceId).map(w -> w.getId()).orElse(-1L);
        var rows = store.all().stream().filter(a -> workspaceIds.contains(a.workspaceId()))
                .filter(a -> selected == null || a.workspaceId() == selected).toList();
        return page(actor, rows, page, size, false);
    }

    public PageResponse<GpuAllocationView> adminList(AuthenticatedUser actor, UUID orgId, UUID workspaceId, GpuAllocationStatus status, int page, int size) {
        Long selected = orgId == null ? null : orgs.findByPublicId(orgId).map(o -> o.getId()).orElse(-1L);
        Long selectedWorkspace = workspaceId == null ? null : workspaces.findByPublicId(workspaceId).map(w -> w.getId()).orElse(-1L);
        var rows = store.all().stream().filter(a -> actor.role().isSysTier() || actor.reads(a.orgId()))
                .filter(a -> selectedWorkspace == null || a.workspaceId() == selectedWorkspace)
                .filter(a -> status == null || a.status() == status)
                .filter(a -> selected == null || a.orgId() == selected)
                .sorted(status == GpuAllocationStatus.QUEUED
                        ? java.util.Comparator.comparingInt(GpuAllocation::priority).reversed().thenComparing(GpuAllocation::queuedAt).thenComparingLong(GpuAllocation::id)
                        : java.util.Comparator.comparing(GpuAllocation::createdAt).reversed().thenComparing(java.util.Comparator.comparingLong(GpuAllocation::id).reversed()))
                .toList();
        return page(actor, rows, page, size, true);
    }

    private PageResponse<GpuAllocationView> page(AuthenticatedUser actor, List<GpuAllocation> rows, int page, int size, boolean admin) {
        return new PageResponse<>(rows.stream().skip((long) page * size).limit(size).map(a -> view(actor, a, admin)).toList(),
                page, size, rows.size(), (rows.size() + size - 1) / size);
    }

    public GpuAllocationView get(AuthenticatedUser actor, UUID id) {
        GpuAllocation a = requireVisible(actor, id);
        return view(actor, a, false);
    }

    public GpuAllocationView adminGet(AuthenticatedUser actor, UUID id) {
        GpuAllocation a = store.allocation(id).orElseThrow(GpuErrors::notFound);
        requireAdminRead(actor, a);
        return view(actor, a, true);
    }

    public GpuAllocation requireVisible(AuthenticatedUser actor, UUID id) {
        GpuAllocation a = store.allocation(id).orElseThrow(GpuErrors::notFound);
        standing(actor, a).requireVisible(GpuResourceAdapter.MESSAGES);
        return a;
    }

    public ResourceStanding standing(AuthenticatedUser actor, GpuAllocation a) {
        return access.standing(ResourceType.GPU, a.id(), a.workspaceId(), actor.id());
    }

    public void requireAdminRead(AuthenticatedUser actor, GpuAllocation a) {
        if (!actor.role().isSysTier() && !actor.reads(a.orgId())) { throw GpuErrors.notFound(); }
    }

    public GpuAllocationView view(AuthenticatedUser actor, GpuAllocation a, boolean admin) {
        var workspace = workspaces.findById(a.workspaceId()).orElseThrow();
        var standing = standing(actor, a);
        boolean limited = !admin && standing.role() == null;
        var ownerNames = access.listAccess(ResourceType.GPU, List.of(a.id()), actor.id()).ownerNames().getOrDefault(a.id(), List.of());
        var org = orgs.findById(a.orgId()).orElseThrow();
        var vm = a.vmId() == null ? null : vms.findById(a.vmId()).orElse(null);
        var samples = !limited && a.connectionStatus() == GpuConnectionStatus.ATTACHED && a.attachedAt() != null
                ? store.jdbc().query("select sampled_at, util_percent from gpu_utilization_samples where allocation_id = ? and sampled_at >= ? and confidence > 0 order by sampled_at desc limit 1",
                    (rs, row) -> Map.entry(GpuStore.instant(rs, "sampled_at"), rs.getDouble("util_percent")), a.id(), java.sql.Timestamp.from(a.attachedAt()))
                : java.util.List.<java.util.Map.Entry<java.time.Instant, Double>>of();
        return new GpuAllocationView(a.publicId(), a.name(), a.status(), a.connectionStatus(), workspace.getPublicId(), workspace.getName(),
                limited ? null : org.getPublicId(), limited ? null : org.getName(), limited, ownerNames, standing.manages(),
                limited ? null : standing.role(), limited || a.gpuId() == null ? null : gpuView(store.gpu(a.gpuId()).orElseThrow()),
                limited || vm == null ? null : vm.getPublicId(), limited || vm == null ? null : vm.getName(),
                limited ? null : a.grantedLeaseHours(), limited ? null : a.allocatedAt(), limited ? null : a.leaseEndsAt(),
                limited ? null : a.unattachedSince(), limited || a.status() != GpuAllocationStatus.QUEUED ? null : store.queuePosition(a),
                limited ? null : a.priority(), samples.isEmpty() ? null : samples.getFirst().getValue(), samples.isEmpty() ? null : samples.getFirst().getKey(), limited ? null : a.releaseReason(), limited ? null : a.error(), a.createdAt(), a.updatedAt());
    }
}
