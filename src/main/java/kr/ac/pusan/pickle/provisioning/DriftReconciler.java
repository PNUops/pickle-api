package kr.ac.pusan.pickle.provisioning;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Recurring 10-minute DB↔Proxmox drift detection (docs/plan/03
 * "reconciliation"): DB is intent, Proxmox is reality, humans resolve. Three
 * drift classes, <b>none of which ever destroys anything</b>:
 *
 * <ol>
 *   <li>DB VM missing in Proxmox → CAS to NEEDS_ADMIN ({@link #DETAIL_MISSING})
 *       + persisted {@code MISSING_IN_PROXMOX} finding.</li>
 *   <li>pickle-tagged Proxmox qemu VM unknown to the DB → persisted
 *       {@code UNMANAGED_GUEST} finding (M5 drift report); never touched.</li>
 *   <li>Spec mismatch (maxcpu/maxmem vs granted vcpu/memory_mb) →
 *       informational {@code status_detail} flag + persisted
 *       {@code SPEC_MISMATCH} finding, no state transition.</li>
 * </ol>
 *
 * <p>Every observation is an upsert keyed on (kind, dedup_key) — one OPEN
 * finding per condition, {@code last_seen_at} bumped on re-observation. At the
 * end of each cycle, OPEN findings whose key was not seen are auto-resolved
 * ({@code resolved_by} null) — but never because we merely failed to look:
 * VMs on an OFFLINE or listing-failed node count as seen for the per-VM kinds
 * (state unknown → keep), and UNMANAGED_GUEST findings are skipped from
 * auto-resolve on a cycle where any listing <i>failed</i> (their guests are
 * anonymous, so partial scope cannot be attributed per finding). An
 * operator-set OFFLINE node is treated as scope-excluded instead — otherwise a
 * long decommission would freeze the ② report forever.</p>
 *
 * <p>VMs with a live provisioning task are skipped (the pipeline is mid-flight
 * and transient inconsistency is expected) — their keys still count as seen so
 * an existing finding survives the flight instead of flapping. Errors are
 * logged and swallowed per node so a broken node cannot stop the recurring
 * job.</p>
 */
@Component
public class DriftReconciler {

    public static final String JOB_ID = "drift-reconciler";

    static final String DETAIL_MISSING = "Proxmox에 VM 없음(드리프트)";
    static final String SPEC_DRIFT_PREFIX = "사양 불일치(드리프트)";

    private static final Logger log = LoggerFactory.getLogger(DriftReconciler.class);

    private static final long MIB = 1024L * 1024L;

    private final NodeRepository nodeRepository;
    private final VmRepository vmRepository;
    private final ProvisioningTaskRepository taskRepository;
    private final DriftFindingRepository driftFindingRepository;
    private final ProxmoxClient proxmoxClient;
    private final ObjectMapper objectMapper;

    public DriftReconciler(NodeRepository nodeRepository, VmRepository vmRepository,
            ProvisioningTaskRepository taskRepository,
            DriftFindingRepository driftFindingRepository, ProxmoxClient proxmoxClient,
            ObjectMapper objectMapper) {
        this.nodeRepository = nodeRepository;
        this.vmRepository = vmRepository;
        this.taskRepository = taskRepository;
        this.driftFindingRepository = driftFindingRepository;
        this.proxmoxClient = proxmoxClient;
        this.objectMapper = objectMapper;
    }

    /**
     * One reconcile cycle. Public and argument-free so JobRunr's
     * {@code RecurringJobPostProcessor} (spring-boot-4 starter 8.7.1) can
     * register it; tests call it directly.
     */
    @Recurring(id = JOB_ID, interval = "PT10M")
    @Job(name = JOB_ID, retries = 0)
    public void reconcile() {
        try {
            Cycle cycle = new Cycle();
            List<Vm> withVmid = vmRepository.findByProxmoxVmidIsNotNull();
            Set<Long> liveTaskVmIds = taskRepository.findVmIdsWithStatusIn(ProvisioningTaskStatus.live());
            // Drift ①/③ working set: live rows only. DELETED/ERROR are terminal
            // states an operator already knows about (never auto-touched).
            Map<Long, List<Vm>> activeByNode = withVmid.stream()
                    .filter(vm -> vm.getStatus() != VmStatus.DELETED && vm.getStatus() != VmStatus.ERROR)
                    .collect(Collectors.groupingBy(Vm::getNodeId));
            // Drift ② baseline: any non-DELETED row claims its vmid. A DELETED
            // row whose guest still exists is genuine "unmanaged" drift.
            Set<Integer> knownVmids = withVmid.stream()
                    .filter(vm -> vm.getStatus() != VmStatus.DELETED)
                    .map(Vm::getProxmoxVmid)
                    .collect(Collectors.toSet());
            for (Node node : nodeRepository.findAll()) {
                if (node.getStatus() == NodeStatus.OFFLINE) {
                    // Operator took the node out of service: excluded from the
                    // cycle's scope, but its VMs' findings must not flap — hold
                    // their keys as seen (state unknown).
                    cycle.holdVmKeys(activeByNode.getOrDefault(node.getId(), List.of()));
                    continue;
                }
                try {
                    reconcileNode(node, activeByNode.getOrDefault(node.getId(), List.of()),
                            knownVmids, liveTaskVmIds, cycle);
                } catch (RuntimeException e) {
                    // Next cycle retries in 10 min; other nodes still reconcile.
                    cycle.listingFailed(activeByNode.getOrDefault(node.getId(), List.of()));
                    log.warn("drift reconcile failed for node {}: {}", node.getName(), e.toString());
                }
            }
            autoResolve(cycle);
        } catch (RuntimeException e) {
            log.warn("drift reconcile cycle failed: {}", e.toString());
        }
    }

    private void reconcileNode(Node node, List<Vm> nodeVms, Set<Integer> knownVmids,
            Set<Long> liveTaskVmIds, Cycle cycle) {
        // Cluster-wide listing: existence is matched by vmid alone, so a VM
        // migrated to a sibling cluster node is not falsely flagged missing.
        Map<Integer, ClusterResource> qemuByVmid = new HashMap<>();
        for (ClusterResource resource : proxmoxClient.clusterResources(node.getApiHost(), "vm")) {
            if (resource.vmid() != null && "qemu".equals(resource.type())) {
                qemuByVmid.put(resource.vmid(), resource);
            }
        }

        for (Vm vm : nodeVms) {
            if (liveTaskVmIds.contains(vm.getId())) {
                // Pipeline mid-flight — transient drift is expected. Count the
                // VM as seen for both per-VM kinds so nothing auto-resolves.
                cycle.seen(DriftFindingKind.MISSING_IN_PROXMOX, vmKey(vm));
                cycle.seen(DriftFindingKind.SPEC_MISMATCH, vmKey(vm));
                continue;
            }
            ClusterResource resource = qemuByVmid.get(vm.getProxmoxVmid());
            if (resource == null) {
                flagMissing(vm, node, cycle);
            } else {
                reconcileSpec(vm, resource, cycle);
            }
        }

        // Drift ②: pickle-tagged guests nobody in the DB claims — persisted as
        // UNMANAGED_GUEST findings; auto-destroying is forbidden (docs/plan/03).
        for (ClusterResource resource : qemuByVmid.values()) {
            if (!knownVmids.contains(resource.vmid())
                    && ManagedGuestIdentity.hasManagedTag(resource.tags())) {
                String dedupKey = "vmid:" + resource.vmid();
                if (cycle.seen(DriftFindingKind.UNMANAGED_GUEST, dedupKey)) {
                    driftFindingRepository.observe(DriftFindingKind.UNMANAGED_GUEST, null,
                            resource.vmid(), resource.node(),
                            "미관리 pickle 게스트: vmid %d '%s' (노드 %s, 상태 %s)".formatted(
                                    resource.vmid(), resource.name(), resource.node(),
                                    resource.status()),
                            detailJson(Map.of(
                                    "name", String.valueOf(resource.name()),
                                    "status", String.valueOf(resource.status()))),
                            dedupKey, Instant.now());
                }
                log.warn("unmanaged pickle-tagged VM on Proxmox: vmid {} name '{}' node {} status {}"
                                + " — not in DB, leaving untouched (docs/plan/03)",
                        resource.vmid(), resource.name(), resource.node(), resource.status());
            }
        }
    }

    /** Drift ①: parks the VM for an operator (skips VMs already parked) + persists the finding. */
    private void flagMissing(Vm vm, Node node, Cycle cycle) {
        String dedupKey = vmKey(vm);
        if (cycle.seen(DriftFindingKind.MISSING_IN_PROXMOX, dedupKey)) {
            driftFindingRepository.observe(DriftFindingKind.MISSING_IN_PROXMOX, vm.getId(),
                    vm.getProxmoxVmid(), node.getName(),
                    "Proxmox에 VM 없음: %s (vmid %d)".formatted(vm.getHostname(), vm.getProxmoxVmid()),
                    null, dedupKey, Instant.now());
        }
        if (vm.getStatus() == VmStatus.NEEDS_ADMIN) {
            return;
        }
        if (vmRepository.transitionStatus(vm.getId(), vm.getStatus(), VmStatus.NEEDS_ADMIN,
                DETAIL_MISSING, Instant.now()) == 1) {
            log.warn("vm {} (vmid {}) missing in Proxmox — flagged NEEDS_ADMIN",
                    vm.getId(), vm.getProxmoxVmid());
        }
    }

    /**
     * Drift ③: informational {@code status_detail} flag on spec mismatch, no
     * state transition, + persisted finding. Cleared again once the specs agree
     * (only if the current detail is a spec-drift note — never wipes pipeline
     * error messages).
     */
    private void reconcileSpec(Vm vm, ClusterResource resource, Cycle cycle) {
        if (resource.maxcpu() == null || resource.maxmem() == null) {
            return;
        }
        boolean mismatch = resource.maxcpu() != vm.getVcpu()
                || resource.maxmem() != vm.getMemoryMb() * MIB;
        String current = vm.getStatusDetail();
        if (mismatch) {
            String detail = "%s: Proxmox %dvCPU/%dMB ≠ DB %dvCPU/%dMB".formatted(SPEC_DRIFT_PREFIX,
                    resource.maxcpu(), resource.maxmem() / MIB, vm.getVcpu(), vm.getMemoryMb());
            String dedupKey = vmKey(vm);
            if (cycle.seen(DriftFindingKind.SPEC_MISMATCH, dedupKey)) {
                driftFindingRepository.observe(DriftFindingKind.SPEC_MISMATCH, vm.getId(),
                        vm.getProxmoxVmid(), resource.node(),
                        "사양 불일치: %s — Proxmox %dvCPU/%dMB ≠ DB %dvCPU/%dMB".formatted(
                                vm.getHostname(), resource.maxcpu(), resource.maxmem() / MIB,
                                vm.getVcpu(), vm.getMemoryMb()),
                        detailJson(Map.of(
                                "expected", Map.of("vcpu", vm.getVcpu(),
                                        "memoryMb", vm.getMemoryMb()),
                                "actual", Map.of("vcpu", resource.maxcpu(),
                                        "memoryMb", resource.maxmem() / MIB))),
                        dedupKey, Instant.now());
            }
            if (!detail.equals(current)
                    && vmRepository.updateStatusDetail(vm.getId(), vm.getStatus(), detail,
                            Instant.now()) == 1) {
                log.info("vm {} (vmid {}) spec drift: {}", vm.getId(), vm.getProxmoxVmid(), detail);
            }
        } else if (current != null && current.startsWith(SPEC_DRIFT_PREFIX)) {
            vmRepository.updateStatusDetail(vm.getId(), vm.getStatus(), null, Instant.now());
        }
    }

    /**
     * End of cycle: auto-resolve OPEN findings whose condition was no longer
     * observed. Per-VM kinds are always safe (unobserved VMs were marked seen);
     * UNMANAGED_GUEST only auto-resolves when every node was listed, because a
     * guest of an unlisted node cannot be told apart from a vanished one.
     */
    private void autoResolve(Cycle cycle) {
        Instant now = Instant.now();
        for (DriftFindingKind kind : DriftFindingKind.values()) {
            if (kind == DriftFindingKind.UNMANAGED_GUEST && !cycle.allNodesObserved) {
                log.info("drift cycle incomplete (node offline or listing failure)"
                        + " — skipping UNMANAGED_GUEST auto-resolve");
                continue;
            }
            int resolved = driftFindingRepository.autoResolveNotSeen(kind,
                    cycle.seenKeys.getOrDefault(kind, Set.of()), now);
            if (resolved > 0) {
                log.info("auto-resolved {} {} drift finding(s) no longer observed", resolved, kind);
            }
        }
    }

    private static String vmKey(Vm vm) {
        return "vm:" + vm.getId();
    }

    private String detailJson(Map<String, ?> detail) {
        return objectMapper.writeValueAsString(detail);
    }

    /** Per-cycle observation state: seen dedup keys per kind + full-scope flag. */
    private static final class Cycle {
        final Map<DriftFindingKind, Set<String>> seenKeys = new EnumMap<>(DriftFindingKind.class);
        boolean allNodesObserved = true;

        /** Marks the key seen; {@code true} when first seen this cycle (dedup across nodes). */
        boolean seen(DriftFindingKind kind, String key) {
            return seenKeys.computeIfAbsent(kind, k -> new HashSet<>()).add(key);
        }

        /** A node was not listed: its VMs' state is unknown — hold their findings as-is. */
        void holdVmKeys(List<Vm> nodeVms) {
            for (Vm vm : nodeVms) {
                seen(DriftFindingKind.MISSING_IN_PROXMOX, vmKey(vm));
                seen(DriftFindingKind.SPEC_MISMATCH, vmKey(vm));
            }
        }

        /** Listing failed (node should have been observable): breaks the ② guard too. */
        void listingFailed(List<Vm> nodeVms) {
            allNodesObserved = false;
            holdVmKeys(nodeVms);
        }
    }

}
