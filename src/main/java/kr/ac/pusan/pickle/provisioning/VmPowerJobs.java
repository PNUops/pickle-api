package kr.ac.pusan.pickle.provisioning;

import java.time.Instant;
import java.util.Set;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.proxmox.ProxmoxTaskFailedException;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmEvent;
import kr.ac.pusan.pickle.vm.VmEventRepository;
import kr.ac.pusan.pickle.vm.VmEventType;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Single-shot power jobs (docs/plan/03 power operations). The API endpoint
 * validated permission and state and enqueued after commit; this worker calls
 * Proxmox, awaits the UPID and records the outcome:
 *
 * <ul>
 *   <li>success — CAS status transition + a {@code vm_events} row with the
 *       requesting user as actor;</li>
 *   <li>failure — the status is left alone (the M3 status poller converges it
 *       with reality); the failure is recorded in {@code vms.status_detail}
 *       and in the event's {@code detail}.</li>
 * </ul>
 *
 * <p>No JobRunr retries ({@code retries = 0}): a power action is a one-shot
 * user intent, and blind re-runs against a VM that moved on would only produce
 * misleading PVE errors. Each job re-checks the DB status first, so a stale or
 * duplicated enqueue skips quietly instead of erroring.</p>
 */
@Component
public class VmPowerJobs {

    private static final Logger log = LoggerFactory.getLogger(VmPowerJobs.class);

    private final VmRepository vmRepository;
    private final VmEventRepository vmEventRepository;
    private final NodeRepository nodeRepository;
    private final ProxmoxClient proxmoxClient;

    public VmPowerJobs(VmRepository vmRepository, VmEventRepository vmEventRepository,
            NodeRepository nodeRepository, ProxmoxClient proxmoxClient) {
        this.vmRepository = vmRepository;
        this.vmEventRepository = vmEventRepository;
        this.nodeRepository = nodeRepository;
        this.proxmoxClient = proxmoxClient;
    }

    @Job(name = "vm-power start %0", retries = 0)
    public void start(long vmId, long actorId) {
        execute(PowerAction.START, vmId, actorId);
    }

    @Job(name = "vm-power shutdown %0", retries = 0)
    public void shutdown(long vmId, long actorId) {
        execute(PowerAction.SHUTDOWN, vmId, actorId);
    }

    @Job(name = "vm-power reboot %0", retries = 0)
    public void reboot(long vmId, long actorId) {
        execute(PowerAction.REBOOT, vmId, actorId);
    }

    @Job(name = "vm-power force-stop %0", retries = 0)
    public void forceStop(long vmId, long actorId) {
        execute(PowerAction.FORCE_STOP, vmId, actorId);
    }

    private void execute(PowerAction action, long vmId, long actorId) {
        Vm vm = vmRepository.findById(vmId).orElse(null);
        if (vm == null) {
            log.warn("Power job {} skipped: vm {} not found", action, vmId);
            return; // no row → no claim to release
        }
        // Release the start/shutdown/force-stop claim on every exit path so a
        // finished (or skipped, or failed) action never bricks power controls.
        // Reboot never set the claim, so this is a harmless no-op for it.
        try {
            run(action, vm, vmId, actorId);
        } finally {
            vmRepository.clearPowerActionClaim(vmId, Instant.now());
        }
    }

    private void run(PowerAction action, Vm vm, long vmId, long actorId) {
        VmStatus from = vm.getStatus();
        if (!action.fromStatuses.contains(from)) {
            // Stale/duplicate enqueue, or the VM moved on (e.g. deletion won).
            log.info("Power job {} skipped for vm {} (status {})", action, vmId, from);
            return;
        }
        if (vm.getProxmoxVmid() == null) {
            recordFailure(action, vmId, actorId, "Proxmox VMID가 없는 VM입니다");
            return;
        }
        Node node = nodeRepository.findById(vm.getNodeId()).orElse(null);
        if (node == null) {
            recordFailure(action, vmId, actorId, "배치된 노드 정보를 찾을 수 없습니다");
            return;
        }
        try {
            String upid = action.invoke(proxmoxClient, node.getApiHost(), node.getName(),
                    vm.getProxmoxVmid());
            proxmoxClient.awaitTask(node.getApiHost(), node.getName(), upid);
            int updated = vmRepository.transitionStatus(vmId, from, action.toStatus, null, Instant.now());
            if (updated == 1) {
                vmEventRepository.save(new VmEvent(vmId, action.eventType, actorId, null));
                log.info("Power job {} completed for vm {} ({} → {})", action, vmId, from, action.toStatus);
            } else {
                log.info("Power job {} lost the CAS for vm {} — already transitioned elsewhere",
                        action, vmId);
            }
        } catch (RuntimeException e) {
            recordFailure(action, vmId, actorId, reasonOf(e));
        }
    }

    /** Failure: keep the status (the poller converges), record detail + event. */
    private void recordFailure(PowerAction action, long vmId, long actorId, String reason) {
        String detail = action.koreanLabel + " 실패: " + reason;
        log.warn("Power job {} failed for vm {}: {}", action, vmId, reason);
        vmRepository.updateStatusDetail(vmId, detail, Instant.now());
        vmEventRepository.save(new VmEvent(vmId, action.eventType, actorId, detail));
    }

    private static String reasonOf(RuntimeException e) {
        if (e instanceof ProxmoxTaskFailedException taskFailed) {
            return taskFailed.exitstatus();
        }
        return e.getMessage();
    }

    /** Allowed source statuses / result status / event type per contract op. */
    private enum PowerAction {
        START(Set.of(VmStatus.STOPPED), VmStatus.RUNNING, VmEventType.START, "시작") {
            @Override
            String invoke(ProxmoxClient client, String apiHost, String node, int vmid) {
                return client.start(apiHost, node, vmid);
            }
        },
        SHUTDOWN(Set.of(VmStatus.RUNNING), VmStatus.STOPPED, VmEventType.STOP, "종료") {
            @Override
            String invoke(ProxmoxClient client, String apiHost, String node, int vmid) {
                // Contract shutdownVm: no force-stop fallback — an unresponsive
                // guest surfaces as a recorded failure and the user chooses.
                return client.shutdown(apiHost, node, vmid);
            }
        },
        REBOOT(Set.of(VmStatus.REBOOTING), VmStatus.RUNNING, VmEventType.REBOOT, "재부팅") {
            @Override
            String invoke(ProxmoxClient client, String apiHost, String node, int vmid) {
                return client.reboot(apiHost, node, vmid);
            }
        },
        FORCE_STOP(Set.of(VmStatus.RUNNING, VmStatus.REBOOTING), VmStatus.STOPPED,
                VmEventType.FORCE_STOP, "강제 종료") {
            @Override
            String invoke(ProxmoxClient client, String apiHost, String node, int vmid) {
                return client.stop(apiHost, node, vmid);
            }
        };

        final Set<VmStatus> fromStatuses;
        final VmStatus toStatus;
        final VmEventType eventType;
        final String koreanLabel;

        PowerAction(Set<VmStatus> fromStatuses, VmStatus toStatus, VmEventType eventType,
                String koreanLabel) {
            this.fromStatuses = fromStatuses;
            this.toStatus = toStatus;
            this.eventType = eventType;
            this.koreanLabel = koreanLabel;
        }

        abstract String invoke(ProxmoxClient client, String apiHost, String node, int vmid);
    }
}
