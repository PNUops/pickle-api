package kr.ac.pusan.pickle.networkpolicy;

import java.util.List;
import kr.ac.pusan.pickle.config.VmFirewallPolicyProperties;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.ipam.AllocationStatus;
import kr.ac.pusan.pickle.ipam.IpAllocation;
import kr.ac.pusan.pickle.ipam.IpAllocationRepository;
import kr.ac.pusan.pickle.networkpolicy.VmFirewallBarrierReconciler.FailedClosedException;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Reconciles one durable policy without holding a DB row transaction across PVE calls. */
@Component
public class VmNetworkPolicyApplyJob {

    private static final Logger log = LoggerFactory.getLogger(VmNetworkPolicyApplyJob.class);

    private final VmNetworkPolicyStore store;
    private final VmNetworkPolicyAdvisoryLock lock;
    private final VmRepository vms;
    private final NodeRepository nodes;
    private final IpAllocationRepository allocations;
    private final VmFirewallPolicyProperties properties;
    private final VmNetworkPublishedPathStore publishedPaths;
    private final VmFirewallBarrierReconciler reconciler;

    public VmNetworkPolicyApplyJob(VmNetworkPolicyStore store,
            VmNetworkPolicyAdvisoryLock lock, VmRepository vms, NodeRepository nodes,
            IpAllocationRepository allocations, VmFirewallPolicyProperties properties,
            VmNetworkPublishedPathStore publishedPaths, VmFirewallBarrierReconciler reconciler) {
        this.store = store;
        this.lock = lock;
        this.vms = vms;
        this.nodes = nodes;
        this.allocations = allocations;
        this.properties = properties;
        this.publishedPaths = publishedPaths;
        this.reconciler = reconciler;
    }

    @Job(name = "vm-network-policy %0", retries = 3)
    public void apply(long vmId) {
        if (!lock.run(vmId, () -> applyLocked(vmId))) {
            throw new IllegalStateException("VM 통신 정책을 다른 worker가 적용 중입니다.");
        }
    }

    private void applyLocked(long vmId) {
        VmNetworkPolicyStore.Snapshot snapshot = store.find(vmId).orElse(null);
        if (snapshot == null) {
            return; // durable opt-in absent: legacy behavior
        }
        VmNetworkPolicyStore.Target target = null;
        try {
            properties.requireConfigured(); // existing row never bypasses on flag disappearance
            Vm vm = vms.findById(vmId).orElseThrow(() -> new IllegalStateException("VM이 존재하지 않습니다."));
            Node node = nodes.findById(vm.getNodeId()).orElseThrow();
            node.vmFirewallPolicy().orElseThrow(() -> new IllegalStateException(
                    "노드의 VM 방화벽 정책 준비 label이 없습니다."));
            var nicRequirements = node.vmNicRequirements().orElseThrow(() ->
                    new IllegalStateException("노드의 VM NIC 준비 label이 없습니다."));
            if (vm.getProxmoxVmid() == null) {
                throw new IllegalStateException("VMID가 아직 준비되지 않았습니다.");
            }
            IpAllocation allocation = allocations
                    .findFirstByVmIdAndStatusOrderByIdDesc(vmId, AllocationStatus.ALLOCATED)
                    .orElseThrow(() -> new IllegalStateException("할당된 VM IPv4가 없습니다."));
            List<VmNetworkPolicyCompiler.PublishedPath> paths = publishedPaths.find(vmId);
            String desiredHash = VmNetworkPolicyHashes.desired(properties, paths, snapshot.rules());
            if (!desiredHash.equals(snapshot.desiredHash())) {
                throw new IllegalStateException("VM 통신 정책 desired hash가 현재 입력과 다릅니다.");
            }
            VmNetworkPolicyCompiler.Plan plan = VmNetworkPolicyCompiler.compile(
                    allocation.getIp(), properties, paths, snapshot.rules());
            target = store.target(vmId, nicRequirements.mtu());
            if (target.nodeId() != node.getId()
                    || target.vmid() != vm.getProxmoxVmid()
                    || !target.ip().equals(allocation.getIp())
                    || !target.apiHost().equals(node.getApiHost())
                    || !target.nodeName().equals(node.getName())
                    || !target.bridge().equals(node.getVmBridge())) {
                throw new IllegalStateException("VM 방화벽 target tuple이 적용 입력과 다릅니다.");
            }
            reconciler.reconcile(target.providerTarget(), plan);
            if (!store.markApplied(vmId, snapshot.desiredGeneration(), desiredHash, target)) {
                log.info("vm network policy {} outcome superseded by a newer generation", vmId);
            }
        } catch (FailedClosedException closed) {
            String error = safeMessage(closed);
            if (target != null && store.markFailedClosed(vmId, snapshot.desiredGeneration(),
                    snapshot.desiredHash(), target, error)) {
                return;
            }
            if (!store.markFailed(vmId, snapshot.desiredGeneration(),
                    VmNetworkPolicyApplyState.FAILED,
                    "VM 대상 정보가 변경되어 차단 상태를 확인할 수 없습니다.")) {
                log.info("vm network policy {} failed-closed outcome superseded", vmId);
            }
        } catch (RuntimeException failure) {
            if (store.markFailed(vmId, snapshot.desiredGeneration(),
                    VmNetworkPolicyApplyState.FAILED, safeMessage(failure))) {
                throw failure;
            }
            log.info("vm network policy {} failure superseded by a newer generation", vmId);
        }
    }

    private static String safeMessage(Throwable failure) {
        if (failure instanceof VmFirewallBarrierReconciler.ReconcileException
                && failure.getMessage() != null && !failure.getMessage().isBlank()) {
            return failure.getMessage();
        }
        return "Proxmox VM 방화벽 설정 상태를 확인할 수 없습니다.";
    }
}
