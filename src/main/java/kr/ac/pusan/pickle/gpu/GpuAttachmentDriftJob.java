package kr.ac.pusan.pickle.gpu;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.provisioning.DriftFindingKind;
import kr.ac.pusan.pickle.provisioning.DriftFindingRepository;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.springframework.stereotype.Component;

/** Reports attachment drift without changing devices, power, or card ownership. */
@Component
public class GpuAttachmentDriftJob {
    private final GpuStore store;
    private final VmRepository vms;
    private final NodeRepository nodes;
    private final GpuOperationJob operations;
    private final DriftFindingRepository findings;
    private final Clock clock;
    public GpuAttachmentDriftJob(GpuStore store, VmRepository vms, NodeRepository nodes, GpuOperationJob operations,
            DriftFindingRepository findings, Clock clock) {
        this.store = store; this.vms = vms; this.nodes = nodes; this.operations = operations; this.findings = findings; this.clock = clock;
    }
    @Recurring(id = "gpu-attachment-drift", interval = "PT10M")
    @Job(name = "gpu-attachment-drift", retries = 0)
    public void check() {
        Set<String> held = new HashSet<>();
        for (GpuAllocation a : store.held()) {
            String key = "gpu-allocation:" + a.publicId();
            if (a.operationId() != null || a.connectionStatus() == GpuConnectionStatus.ERROR) { held.add(key); continue; }
            if (a.connectionStatus() != GpuConnectionStatus.ATTACHED || a.vmId() == null) { continue; }
            var vm = vms.findById(a.vmId()).orElse(null);
            if (vm == null || vm.getProxmoxVmid() == null) { held.add(key); continue; }
            var node = nodes.findById(vm.getNodeId()).orElse(null);
            if (node == null) { held.add(key); continue; }
            GpuDeviceState state;
            try { state = operations.inspect(node, vm); }
            catch (RuntimeException e) { held.add(key); continue; }
            Gpu gpu = store.gpu(a.gpuId()).orElseThrow();
            boolean match;
            try { state.requireKnown(gpu); match = state.attached(gpu); }
            catch (IllegalStateException e) { match = false; }
            if (!match) {
                held.add(key);
                findings.observe(DriftFindingKind.GPU_ATTACHMENT_MISMATCH, vm.getId(), vm.getProxmoxVmid(), node.getName(),
                        "GPU 연결 상태 불일치: " + a.name(), null, key, clock.instant());
            }
        }
        findings.autoResolveNotSeen(DriftFindingKind.GPU_ATTACHMENT_MISMATCH, held, clock.instant());
    }
}
