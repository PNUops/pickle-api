package kr.ac.pusan.pickle.networkpolicy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import kr.ac.pusan.pickle.config.VmFirewallPolicyProperties;
import kr.ac.pusan.pickle.networkpolicy.VmNetworkPathOperationStore.Action;
import kr.ac.pusan.pickle.networkpolicy.VmNetworkPathOperationStore.Operation;
import kr.ac.pusan.pickle.networkpolicy.VmNetworkPathOperationStore.OwnerKind;
import kr.ac.pusan.pickle.networkpolicy.VmNetworkPathOperationStore.Phase;
import kr.ac.pusan.pickle.publishing.Route;
import kr.ac.pusan.pickle.publishing.RouteApplyJob;
import kr.ac.pusan.pickle.publishing.RouteRepository;
import kr.ac.pusan.pickle.publishing.agent.ApplyOutcome;
import kr.ac.pusan.pickle.relay.RelayGenerations;
import kr.ac.pusan.pickle.relay.RelayMappingRetirementStore;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable ordering: VM allow before PRESENT, consumer retirement before allow removal. */
@Component
public class VmNetworkPathCoordinatorJob {

    public static final String JOB_ID = "vm-network-path-reconcile";
    private static final Logger log = LoggerFactory.getLogger(VmNetworkPathCoordinatorJob.class);
    private static final int MAX_PER_CYCLE = 50;

    private final VmNetworkPathOperationStore operations;
    private final VmNetworkPublishedPathStore paths;
    private final VmNetworkPolicyStore policies;
    private final VmNetworkPolicyAdvisoryLock lock;
    private final VmNetworkPolicyApplyJob policyApply;
    private final VmFirewallPolicyProperties properties;
    private final RouteRepository routes;
    private final RouteApplyJob routeApply;
    private final RelayGenerations relayGenerations;
    private final RelayMappingRetirementStore retirements;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public VmNetworkPathCoordinatorJob(VmNetworkPathOperationStore operations,
            VmNetworkPublishedPathStore paths, VmNetworkPolicyStore policies,
            VmNetworkPolicyAdvisoryLock lock, VmNetworkPolicyApplyJob policyApply,
            VmFirewallPolicyProperties properties, RouteRepository routes,
            RouteApplyJob routeApply, RelayGenerations relayGenerations,
            RelayMappingRetirementStore retirements, JdbcTemplate jdbc,
            TransactionTemplate transactions) {
        this.operations = operations;
        this.paths = paths;
        this.policies = policies;
        this.lock = lock;
        this.policyApply = policyApply;
        this.properties = properties;
        this.routes = routes;
        this.routeApply = routeApply;
        this.relayGenerations = relayGenerations;
        this.retirements = retirements;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Recurring(id = JOB_ID, interval = "PT15S")
    @Job(name = JOB_ID, retries = 0)
    public void run() {
        operations.due(MAX_PER_CYCLE).forEach(this::processSafely);
    }

    /** Synchronous deletion gate; external ACK waits surface as a retryable hold. */
    public void requireVmRetired(long vmId) {
        for (int pass = 0; pass < 4; pass++) {
            var live = operations.liveForVm(vmId);
            if (live.isEmpty()) {
                return;
            }
            live.forEach(this::process);
        }
        if (!operations.liveForVm(vmId).isEmpty()) {
            throw new IllegalStateException(
                    "외부 경로 retirement ACK와 VM 방화벽 제거 적용을 기다리고 있습니다.");
        }
    }

    @Job(name = "vm-network-path %0", retries = 0)
    public void process(UUID operationId) {
        Operation operation = operations.find(operationId).orElse(null);
        if (operation == null || operation.phase() == Phase.DONE) {
            return;
        }
        switch (operation.phase()) {
            case POLICY_ADD -> policyAdd(operation);
            case CONSUMER_APPLY -> consumerApply(operation);
            case CONSUMER_RETIRE -> consumerRetire(operation);
            case POLICY_REMOVE -> policyRemove(operation);
            case DONE -> { }
        }
    }

    private void processSafely(UUID operationId) {
        try {
            process(operationId);
        } catch (RuntimeException failure) {
            operations.retry(operationId, failure);
            log.warn("VM network path operation {} deferred: {}", operationId,
                    failure.getMessage());
        }
    }

    private void policyAdd(Operation operation) {
        long generation = convergePolicy(operation.vmId());
        if (operation.ownerKind() == OwnerKind.HTTP_ROUTE) {
            operations.move(operation, Phase.CONSUMER_APPLY, generation, null, null);
            return;
        }
        activatePortConsumerAndMove(operation, generation);
    }

    private void consumerApply(Operation operation) {
        if (operation.ownerKind() == OwnerKind.HTTP_ROUTE) {
            ApplyOutcome.Kind outcome = routeApply.applyNow(operation.ownerId());
            if (outcome != ApplyOutcome.Kind.APPLIED && outcome != ApplyOutcome.Kind.STALE) {
                throw new IllegalStateException("HTTP consumer가 경로 적용을 확인하지 않았습니다.");
            }
            Route route = routes.findById(operation.ownerId()).orElseThrow();
            if (route.getAppliedGeneration() == null
                    || route.getAppliedGeneration() < route.getGeneration()) {
                throw new IllegalStateException("HTTP consumer generation ACK를 기다리고 있습니다.");
            }
            if (operation.oldPathId() != null) {
                operations.finishHttpConsumer(operation, Phase.POLICY_REMOVE,
                        route.getGeneration());
            } else {
                operations.finishHttpConsumer(operation, Phase.DONE, route.getGeneration());
            }
            return;
        }
        if (!relayApplied(operation.ownerId(), operation.consumerGeneration())) {
            throw new IllegalStateException("relay consumer generation ACK를 기다리고 있습니다.");
        }
        finishPortConsumer(operation);
    }

    private void consumerRetire(Operation operation) {
        RelayMappingRetirementStore.Retirement retirement =
                retirements.findByMapping(operation.ownerId());
        if (retirement == null) {
            retirement = retirements.begin(operation.ownerId());
            if (!operations.move(operation, Phase.CONSUMER_RETIRE,
                    null, retirement.generation(), retirement.id())) {
                return;
            }
            operation = operations.find(operation.id()).orElseThrow();
        }
        if (!retirements.confirmed(retirement)) {
            throw new IllegalStateException("exact relay retirement receipt를 기다리고 있습니다.");
        }
        operations.finishRetirement(operation);
    }

    private void policyRemove(Operation operation) {
        if (operation.oldPathId() != null) {
            if (!operations.removeOldPath(operation)) {
                return;
            }
            operation = operations.find(operation.id()).orElseThrow();
        }
        long generation = convergePolicy(operation.vmId());
        Operation current = operation;
        transactions.executeWithoutResult(ignored -> finishPolicyRemoval(current, generation));
    }

    private void finishPolicyRemoval(Operation operation, long generation) {
        if (operation.ownerKind() != OwnerKind.PORT_MAPPING) {
            operations.move(operation, Phase.DONE, generation, null, null);
            return;
        }
        RelayMappingRetirementStore.Retirement retirement = operation.retirementId() == null
                ? null : retirements.findByMapping(operation.ownerId());
        if (operation.retirementId() != null
                && (retirement == null || !retirements.confirmed(retirement))) {
            throw new IllegalStateException("ACK된 relay retirement 정본을 찾을 수 없습니다.");
        }
        if (operation.action() == Action.SUSPEND) {
            jdbc.update("""
                    update port_mappings set status = 'SUSPENDED',
                           delivery_state = 'SUSPENDED', updated_at = now()
                     where id = ? and status = 'SUSPENDED'
                    """, operation.ownerId());
        }
        if (!operations.move(operation, Phase.DONE, generation, null, null)) {
            throw new IllegalStateException("경로 작업 완료 상태가 변경되었습니다.");
        }
        if (retirement != null && !retirements.deleteAcknowledged(retirement)) {
            throw new IllegalStateException("ACK된 relay retirement을 정리하지 못했습니다.");
        }
        if (operation.action() == Action.DELETE) {
            jdbc.update("delete from port_mappings where id = ? and status = 'REMOVING'",
                    operation.ownerId());
        }
    }

    private long convergePolicy(long vmId) {
        AtomicReference<VmNetworkPolicyStore.Snapshot> desired = new AtomicReference<>();
        if (!lock.run(vmId, () -> {
            VmNetworkPolicyStore.Snapshot current = policies.find(vmId)
                    .orElseThrow(VmNetworkPolicyStore::unavailable);
            String hash = VmNetworkPolicyHashes.desired(
                    properties, paths.find(vmId), current.rules());
            desired.set(policies.refreshDerived(vmId, hash));
        })) {
            throw VmNetworkPolicyStore.unavailable("VM 통신 정책을 다른 작업이 처리 중입니다.");
        }
        policyApply.apply(vmId);
        VmNetworkPolicyStore.Snapshot applied = policies.find(vmId)
                .orElseThrow(VmNetworkPolicyStore::unavailable);
        if (applied.state() != VmNetworkPolicyApplyState.APPLIED
                || applied.appliedGeneration() == null
                || applied.appliedGeneration() != applied.desiredGeneration()
                || !applied.desiredHash().equals(applied.appliedHash())) {
            throw new IllegalStateException("VM derived allow 적용을 기다리고 있습니다.");
        }
        return applied.desiredGeneration();
    }

    private void activatePortConsumerAndMove(Operation operation, long policyGeneration) {
        activatePortConsumerAndMove(operation, policyGeneration, () -> { });
    }

    /** Package-visible fault seam verifies rollback between mapping activation and phase CAS. */
    void activatePortConsumerAndMove(Operation operation, long policyGeneration,
            Runnable afterMappingActivated) {
        transactions.executeWithoutResult(status -> {
            Boolean current = jdbc.queryForObject("""
                    select phase = 'POLICY_ADD' and revision = ?
                      from vm_network_path_operations where id = ? for update
                    """, Boolean.class, operation.revision(), operation.id());
            if (!Boolean.TRUE.equals(current)) {
                return;
            }
            Long relayId = jdbc.queryForObject(
                    "select relay_id from port_mappings where id = ?",
                    Long.class, operation.ownerId());
            long generation = relayGenerations.bump(relayId);
            int updated = jdbc.update("""
                    update port_mappings set delivery_state = 'ACTIVE',
                           last_change_generation = ?, updated_at = now()
                     where id = ? and status = 'PENDING' and delivery_state = 'PENDING'
                    """, generation, operation.ownerId());
            if (updated != 1) {
                throw new IllegalStateException("port mapping activation 상태가 변경되었습니다.");
            }
            afterMappingActivated.run();
            if (!operations.move(operation, Phase.CONSUMER_APPLY,
                    policyGeneration, generation, null)) {
                throw new IllegalStateException("port mapping activation 작업 상태가 변경되었습니다.");
            }
        });
    }

    /** The operation CAS and public ACTIVE state commit together for one consumer epoch. */
    boolean finishPortConsumer(Operation operation) {
        Boolean finished = transactions.execute(status -> {
            if (!operations.move(operation, Phase.DONE, null, null, null)) {
                return false;
            }
            int updated = jdbc.update("""
                    update port_mappings set status = 'ACTIVE', updated_at = now()
                     where id = ? and status = 'PENDING' and delivery_state = 'ACTIVE'
                    """, operation.ownerId());
            if (updated != 1) {
                throw new IllegalStateException("port mapping consumer 완료 상태가 변경되었습니다.");
            }
            return true;
        });
        return Boolean.TRUE.equals(finished);
    }

    private boolean relayApplied(long mappingId, Long generation) {
        if (generation == null) {
            return false;
        }
        Boolean applied = jdbc.queryForObject("""
                select r.applied_generation >= ?
                  from port_mappings m join relays r on r.id = m.relay_id
                 where m.id = ?
                """, Boolean.class, generation, mappingId);
        return Boolean.TRUE.equals(applied);
    }
}
