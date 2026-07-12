package kr.ac.pusan.pickle.provisioning;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.NodeStatus;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.proxmox.dto.ClusterResource;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Recurring 30-second status poll (docs/plan/03 "Status polling"): reads
 * {@code GET /cluster/resources?type=vm} per node and mirrors UPID-less power
 * state changes (e.g. {@code poweroff} inside the guest) into {@code vms.status}.
 *
 * <p>Only VMs currently RUNNING, STOPPED or REBOOTING are ever touched, and
 * only toward the observed RUNNING/STOPPED guest state (REBOOTING converges
 * once the reboot settles, so a crashed reboot job cannot strand the status);
 * VMs with a live provisioning task or an in-flight power-action claim
 * (docs/plan/03; {@code pending_power_action}) are skipped so
 * the poller never races the pipeline or a deliberate power op. The transition itself is the
 * {@link VmRepository#transitionStatus CAS update}, so losing a race is a
 * harmless no-op. Any error is logged and swallowed — one broken node (or one
 * broken cycle) must not stop the recurring job.</p>
 */
@Component
public class VmStatusPoller {

    public static final String JOB_ID = "vm-status-poller";

    static final String DETAIL_POWERED_OFF = "게스트 전원 꺼짐 감지(상태 폴러)";
    static final String DETAIL_POWERED_ON = "게스트 전원 켜짐 감지(상태 폴러)";

    /** The only states the poller may reconcile — everything else belongs to the pipeline. */
    private static final Set<VmStatus> POLLABLE =
            Set.of(VmStatus.RUNNING, VmStatus.STOPPED, VmStatus.REBOOTING);

    private static final Logger log = LoggerFactory.getLogger(VmStatusPoller.class);

    private final NodeRepository nodeRepository;
    private final VmRepository vmRepository;
    private final ProvisioningTaskRepository taskRepository;
    private final ProxmoxClient proxmoxClient;

    public VmStatusPoller(NodeRepository nodeRepository, VmRepository vmRepository,
            ProvisioningTaskRepository taskRepository, ProxmoxClient proxmoxClient) {
        this.nodeRepository = nodeRepository;
        this.vmRepository = vmRepository;
        this.taskRepository = taskRepository;
        this.proxmoxClient = proxmoxClient;
    }

    /**
     * One poll cycle. Public and argument-free so JobRunr's
     * {@code RecurringJobPostProcessor} (spring-boot-4 starter 8.7.1) can
     * register it; tests call it directly.
     */
    @Recurring(id = JOB_ID, interval = "PT30S")
    @Job(name = JOB_ID, retries = 0)
    public void poll() {
        try {
            List<Vm> candidates = vmRepository.findByProxmoxVmidIsNotNullAndStatusIn(POLLABLE);
            if (candidates.isEmpty()) {
                return;
            }
            Set<Long> liveTaskVmIds = taskRepository.findVmIdsWithStatusIn(ProvisioningTaskStatus.live());
            Map<Long, List<Vm>> vmsByNode = candidates.stream()
                    .collect(Collectors.groupingBy(Vm::getNodeId));
            for (Node node : nodeRepository.findAll()) {
                List<Vm> nodeVms = vmsByNode.get(node.getId());
                if (nodeVms == null || node.getStatus() == NodeStatus.OFFLINE) {
                    continue;
                }
                try {
                    pollNode(node, nodeVms, liveTaskVmIds);
                } catch (RuntimeException e) {
                    // Next cycle retries in 30 s; other nodes still get polled.
                    log.warn("vm status poll failed for node {}: {}", node.getName(), e.toString());
                }
            }
        } catch (RuntimeException e) {
            log.warn("vm status poll cycle failed: {}", e.toString());
        }
    }

    private void pollNode(Node node, List<Vm> nodeVms, Set<Long> liveTaskVmIds) {
        // The response is cluster-wide, so existence is matched by vmid alone;
        // a VM living on an unexpected cluster node is the reconciler's business.
        Map<Integer, VmStatus> actual = new HashMap<>();
        for (ClusterResource resource : proxmoxClient.clusterResources(node.getApiHost(), "vm")) {
            if (resource.vmid() != null && "qemu".equals(resource.type())) {
                VmStatus status = mapStatus(resource.status());
                if (status != null) {
                    actual.put(resource.vmid(), status);
                }
            }
        }
        for (Vm vm : nodeVms) {
            if (liveTaskVmIds.contains(vm.getId()) || vm.getPendingPowerAction() != null) {
                continue; // the pipeline or an in-flight power action owns this VM right now
            }
            VmStatus observed = actual.get(vm.getProxmoxVmid());
            if (observed == null || observed == vm.getStatus()) {
                continue; // missing VMs are the reconciler's drift class ①
            }
            String detail = observed == VmStatus.STOPPED ? DETAIL_POWERED_OFF : DETAIL_POWERED_ON;
            if (vmRepository.transitionStatus(vm.getId(), vm.getStatus(), observed, detail,
                    Instant.now()) == 1) {
                log.info("vm {} (vmid {}) power state drifted: {} → {}", vm.getId(),
                        vm.getProxmoxVmid(), vm.getStatus(), observed);
            }
        }
    }

    /** Maps the PVE resource status; anything but running/stopped is left alone. */
    private static VmStatus mapStatus(String proxmoxStatus) {
        return switch (proxmoxStatus == null ? "" : proxmoxStatus) {
            case "running" -> VmStatus.RUNNING;
            case "stopped" -> VmStatus.STOPPED;
            default -> null;
        };
    }
}
