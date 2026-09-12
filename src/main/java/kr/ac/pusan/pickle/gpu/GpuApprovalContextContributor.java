package kr.ac.pusan.pickle.gpu;

import java.util.List;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.ApprovalContextContributor;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.GpuContext;
import kr.ac.pusan.pickle.request.Request;
import org.springframework.stereotype.Component;

@Component
public class GpuApprovalContextContributor implements ApprovalContextContributor {
    private final GpuStore store;
    public GpuApprovalContextContributor(GpuStore store) { this.store = store; }
    @Override public ResourceType type() { return ResourceType.GPU; }
    @Override public Contribution contribute(Request request, List<Long> applicantWorkspaceIds) {
        var spec = store.requestSpec(request.getId());
        long available = store.jdbc().queryForObject("""
                select count(*) from gpus g join nodes n on n.id=g.node_id
                 where g.status='ACTIVE' and n.status='ACTIVE'
                   and not exists(select 1 from gpu_allocations a where a.gpu_id=g.id and a.status in ('ALLOCATED','RELEASING'))
                """, Long.class);
        long queued = store.all().stream().filter(a -> a.status() == GpuAllocationStatus.QUEUED).count();
        return new Contribution(null, null, new GpuContext(available, queued, spec.leaseHours(), spec.vmName()));
    }
}
