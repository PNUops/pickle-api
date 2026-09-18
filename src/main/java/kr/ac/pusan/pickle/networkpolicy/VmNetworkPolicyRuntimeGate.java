package kr.ac.pusan.pickle.networkpolicy;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import kr.ac.pusan.pickle.config.VmFirewallPolicyProperties;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable opt-in setup and fresh provider proof immediately before power dispatch. */
@Service
public class VmNetworkPolicyRuntimeGate {

    public record DispatchContext(VmNetworkPolicyStore.Target target, String providerStatus) {}

    private final VmNetworkPolicyStore store;
    private final VmNetworkPolicyAdvisoryLock lock;
    private final NodeRepository nodes;
    private final VmFirewallPolicyProperties properties;
    private final VmNetworkPublishedPathStore publishedPaths;
    private final VmFirewallBarrierReconciler reconciler;
    private final TransactionTemplate transactions;

    public VmNetworkPolicyRuntimeGate(VmNetworkPolicyStore store,
            VmNetworkPolicyAdvisoryLock lock, NodeRepository nodes,
            VmFirewallPolicyProperties properties, VmNetworkPublishedPathStore publishedPaths,
            VmFirewallBarrierReconciler reconciler, TransactionTemplate transactions) {
        this.store = store;
        this.lock = lock;
        this.nodes = nodes;
        this.properties = properties;
        this.publishedPaths = publishedPaths;
        this.reconciler = reconciler;
        this.transactions = transactions;
    }

    /** Existing rows always remain gated; new rows require both global and node opt-in. */
    public boolean required(long vmId, Node node) {
        if (store.find(vmId).isPresent()) {
            return true;
        }
        if (!properties.enabled()) {
            return false;
        }
        boolean firewallLabel = node.vmFirewallPolicy().isPresent();
        boolean nicLabel = node.vmNicRequirements().isPresent();
        if (firewallLabel != nicLabel) {
            throw VmNetworkPolicyStore.unavailable("노드의 VM 방화벽 준비 label이 완전하지 않습니다.");
        }
        return firewallLabel;
    }

    public void initialize(long vmId) {
        if (!lock.run(vmId, () -> initializeLocked(vmId))) {
            throw busy();
        }
    }

    private void initializeLocked(long vmId) {
        properties.requireConfigured();
        Node node = preparedNode(vmId);
        VmNetworkPolicyStore.Target target = target(vmId, node);
        if (!"CREATING".equals(target.vmStatus())) {
            throw VmNetworkPolicyStore.unavailable("초기 VM 통신 정책은 생성 중인 VM에만 설정할 수 있습니다.");
        }
        publishedPaths.requireNoLegacyForInitialOptIn(vmId);
        List<VmNetworkPolicyCompiler.PublishedPath> paths = publishedPaths.find(vmId);
        String hash = VmNetworkPolicyHashes.desired(properties, paths, List.of());
        VmNetworkPolicyStore.Snapshot policy = store.initialize(vmId, hash);
        if (policy.revision() != 0 || policy.desiredGeneration() != 1
                || !policy.rules().isEmpty() || !hash.equals(policy.desiredHash())) {
            throw VmNetworkPolicyStore.unavailable("초기 VM 통신 정책 행이 예상 상태와 다릅니다.");
        }
        VmNetworkPolicyCompiler.Plan plan = VmNetworkPolicyCompiler.compile(
                target.ip(), properties, paths, policy.rules());
        reconciler.prepareInitial(target.providerTarget(), plan);
        if (!store.markApplied(vmId, policy.desiredGeneration(), hash, target)) {
            throw VmNetworkPolicyStore.unavailable(
                    "VM 대상 정보가 바뀌어 초기 통신 정책 결과를 반영하지 않았습니다.");
        }
    }

    /**
     * Absent row preserves legacy behavior. A durable row holds the advisory lock from
     * fresh provider proof through command acceptance, and rechecks the DB tuple in a
     * short row transaction without holding it across provider calls.
     */
    public <T> T dispatch(long vmId, String expectedDbStatus, String expectedProviderStatus,
            Function<VmNetworkPolicyStore.Target, T> command) {
        return dispatchWithStatuses(vmId, expectedDbStatus, Set.of(expectedProviderStatus),
                context -> command.apply(context == null ? null : context.target()));
    }

    public <T> T dispatchProvisionStart(long vmId, Function<DispatchContext, T> command) {
        return dispatchWithStatuses(vmId, "CREATING", Set.of("stopped", "running"), command);
    }

    private <T> T dispatchWithStatuses(long vmId, String expectedDbStatus,
            Set<String> expectedProviderStatuses, Function<DispatchContext, T> command) {
        if (store.find(vmId).isEmpty()) {
            return command.apply(null);
        }
        AtomicReference<T> result = new AtomicReference<>();
        if (!lock.run(vmId, () -> result.set(dispatchLocked(vmId, expectedDbStatus,
                expectedProviderStatuses, command)))) {
            throw busy();
        }
        return result.get();
    }

    private <T> T dispatchLocked(long vmId, String expectedDbStatus,
            Set<String> expectedProviderStatuses, Function<DispatchContext, T> command) {
        properties.requireConfigured();
        VmNetworkPolicyStore.Snapshot policy = store.find(vmId)
                .orElseThrow(VmNetworkPolicyStore::unavailable);
        Node node = preparedNode(vmId);
        VmNetworkPolicyStore.Target target = target(vmId, node);
        if (!expectedDbStatus.equals(target.vmStatus())) {
            throw VmNetworkPolicyStore.unavailable("VM 상태가 전원 작업의 통신 정책 gate와 다릅니다.");
        }
        List<VmNetworkPolicyCompiler.PublishedPath> paths = publishedPaths.find(vmId);
        String currentHash = VmNetworkPolicyHashes.desired(properties, paths, policy.rules());
        if (policy.state() != VmNetworkPolicyApplyState.APPLIED
                || policy.appliedGeneration() == null
                || policy.appliedGeneration() != policy.desiredGeneration()
                || !currentHash.equals(policy.desiredHash())
                || !currentHash.equals(policy.appliedHash())) {
            throw VmNetworkPolicyStore.unavailable("최신 VM 통신 정책이 적용된 상태가 아닙니다.");
        }
        VmNetworkPolicyCompiler.Plan plan = VmNetworkPolicyCompiler.compile(
                target.ip(), properties, paths, policy.rules());
        String providerStatus = reconciler.verifyApplied(
                target.providerTarget(), plan, expectedProviderStatuses);
        transactions.executeWithoutResult(ignored -> store.requireDispatchCurrent(target, policy));
        return command.apply(new DispatchContext(target, providerStatus));
    }

    private Node preparedNode(long vmId) {
        VmNetworkPolicyStore.Target provisional = store.target(vmId, 1370);
        Node node = nodes.findById(provisional.nodeId())
                .orElseThrow(() -> VmNetworkPolicyStore.unavailable("VM 노드가 존재하지 않습니다."));
        node.vmFirewallPolicy().orElseThrow(() -> VmNetworkPolicyStore.unavailable(
                "노드의 VM 방화벽 정책 준비 label이 없습니다."));
        node.vmNicRequirements().orElseThrow(() -> VmNetworkPolicyStore.unavailable(
                "노드의 VM NIC 준비 label이 없습니다."));
        return node;
    }

    private VmNetworkPolicyStore.Target target(long vmId, Node node) {
        int mtu = node.vmNicRequirements().orElseThrow().mtu();
        VmNetworkPolicyStore.Target target = store.target(vmId, mtu);
        if (!target.apiHost().equals(node.getApiHost())
                || !target.nodeName().equals(node.getName())
                || !target.bridge().equals(node.getVmBridge())) {
            throw VmNetworkPolicyStore.unavailable("VM 방화벽 target tuple이 노드 정보와 다릅니다.");
        }
        return target;
    }

    private static RuntimeException busy() {
        return VmNetworkPolicyStore.unavailable(
                "VM 통신 정책을 다른 작업이 처리 중입니다. 작성 중인 규칙을 유지하고 다시 시도해 주세요.");
    }
}
