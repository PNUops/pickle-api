package kr.ac.pusan.pickle.networkpolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.VmAccessService;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.config.VmFirewallPolicyProperties;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.networkpolicy.dto.UpdateVmNetworkPolicyRequest;
import kr.ac.pusan.pickle.networkpolicy.dto.VmNetworkPolicyRuleView;
import kr.ac.pusan.pickle.networkpolicy.dto.VmNetworkPolicyView;
import kr.ac.pusan.pickle.networkpolicy.dto.VmNetworkSystemRuleView;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.AdminVmAccess;
import kr.ac.pusan.pickle.vm.Vm;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Public/admin VM-policy access, CAS replacement, audit and async convergence. */
@Service
public class VmNetworkPolicyService {

    private static final List<VmNetworkSystemRuleView> SYSTEM_RULES = List.of(
            new VmNetworkSystemRuleView("SSH_GATEWAY", "플랫폼 SSH gateway에서 VM SSH 접속"),
            new VmNetworkSystemRuleView("WEB_TERMINAL", "웹 터미널 bridge에서 VM SSH 접속"),
            new VmNetworkSystemRuleView("HTTP_PUBLISHING", "활성 도메인의 backend 포트 접속"),
            new VmNetworkSystemRuleView("PORT_FORWARDING", "활성 포트포워딩의 backend 포트 접속"));

    private final VmNetworkPolicyStore store;
    private final VmAccessService access;
    private final AdminVmAccess adminAccess;
    private final NodeRepository nodes;
    private final VmFirewallPolicyProperties properties;
    private final VmNetworkPublishedPathStore publishedPaths;
    private final AuditService audit;
    private final JobScheduler jobs;
    private final VmNetworkPolicyApplyJob applyJob;
    private final VmNetworkPolicyAdvisoryLock lock;
    private final TransactionTemplate transactions;

    public VmNetworkPolicyService(VmNetworkPolicyStore store, VmAccessService access,
            AdminVmAccess adminAccess, NodeRepository nodes,
            VmFirewallPolicyProperties properties, VmNetworkPublishedPathStore publishedPaths,
            AuditService audit,
            JobScheduler jobs, VmNetworkPolicyApplyJob applyJob,
            VmNetworkPolicyAdvisoryLock lock, TransactionTemplate transactions) {
        this.store = store;
        this.access = access;
        this.adminAccess = adminAccess;
        this.nodes = nodes;
        this.properties = properties;
        this.publishedPaths = publishedPaths;
        this.audit = audit;
        this.jobs = jobs;
        this.applyJob = applyJob;
        this.lock = lock;
        this.transactions = transactions;
    }

    @Transactional(readOnly = true)
    public VmNetworkPolicyView userView(AuthenticatedUser actor, UUID vmId) {
        Vm vm = access.of(actor, vmId).requireAtLeast(ResourceRole.VIEWER,
                "VM 통신 정책을 조회할 수 없습니다", "VM 열람자 이상의 접근 권한이 필요합니다.");
        return view(vm);
    }

    public VmNetworkPolicyView userUpdate(AuthenticatedUser actor, UUID vmId,
            UpdateVmNetworkPolicyRequest request, String ip) {
        Vm vm = access.of(actor, vmId).requireAtLeast(ResourceRole.EDITOR,
                "VM 통신 정책을 변경할 수 없습니다", "VM 편집자 이상의 접근 권한이 필요합니다.");
        return updateLocked(vm, actor, request, false, ip);
    }

    @Transactional(readOnly = true)
    public VmNetworkPolicyView adminView(AuthenticatedUser actor, UUID vmId) {
        return view(adminAccess.requireReadableVm(actor, vmId));
    }

    public VmNetworkPolicyView adminUpdate(AuthenticatedUser actor, UUID vmId,
            UpdateVmNetworkPolicyRequest request, String ip) {
        return updateLocked(adminAccess.requireWritableVm(actor, vmId), actor, request, true, ip);
    }

    private VmNetworkPolicyView updateLocked(Vm vm, AuthenticatedUser actor,
            UpdateVmNetworkPolicyRequest request, boolean adminIntervention, String ip) {
        java.util.concurrent.atomic.AtomicReference<VmNetworkPolicyView> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        if (!lock.run(vm.getId(), () -> result.set(transactions.execute(status -> {
            long nodeId = store.requireMutationAllowed(vm.getId());
            return update(vm, nodeId, actor, request, adminIntervention, ip);
        })))) {
            throw VmNetworkPolicyStore.unavailable(
                    "VM 통신 정책을 다른 작업이 처리 중입니다. 작성 중인 규칙을 유지하고 다시 시도해 주세요.");
        }
        return result.get();
    }

    private VmNetworkPolicyView view(Vm vm) {
        var policy = store.find(vm.getId());
        if (policy.isEmpty()) {
            if (!properties.enabled()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_NETWORK_POLICY_DISABLED,
                        "VM 통신 정책 기능이 비활성화되어 있습니다",
                        "이 환경에서는 아직 VM 통신 정책을 사용할 수 없습니다.");
            }
            return new VmNetworkPolicyView(0, List.of(), SYSTEM_RULES,
                    VmNetworkPolicyApplyState.INACTIVE, null, null, null, null);
        }
        return toView(policy.get());
    }

    private VmNetworkPolicyView update(Vm vm, long nodeId, AuthenticatedUser actor,
            UpdateVmNetworkPolicyRequest request, boolean adminIntervention, String ip) {
        List<VmNetworkRule> rules = normalize(request.rules());
        VmNetworkPolicyStore.Snapshot existing = store.find(vm.getId())
                .orElseThrow(VmNetworkPolicyStore::unavailable);
        // A durable row stays a gate even if the flag/label later disappears.
        try {
            properties.requireConfigured();
            var node = nodes.findById(nodeId).orElseThrow();
            node.vmFirewallPolicy().orElseThrow();
            node.vmNicRequirements().orElseThrow();
        } catch (RuntimeException unprepared) {
            throw VmNetworkPolicyStore.unavailable();
        }
        String hash = VmNetworkPolicyHashes.desired(properties, publishedPaths.find(vm.getId()), rules);
        VmNetworkPolicyStore.Snapshot saved = store.replace(vm.getId(),
                request.expectedRevision(), rules, hash, actor.id());
        audit.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.VM_NETWORK_POLICY_UPDATE, "vm", vm.getPublicId(),
                auditDetail(existing.revision(), saved, adminIntervention), ip);
        enqueue(vm.getId());
        return toView(saved);
    }

    private void enqueue(long vmId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                jobs.enqueue(() -> applyJob.apply(vmId));
            }
        });
    }

    private static List<VmNetworkRule> normalize(List<UpdateVmNetworkPolicyRequest.Rule> input) {
        List<VmNetworkRule> result = new ArrayList<>(input.size());
        for (int index = 0; index < input.size(); index++) {
            try {
                UpdateVmNetworkPolicyRequest.Rule rule = input.get(index);
                result.add(new VmNetworkRule(rule.direction(), rule.action(), rule.protocol(),
                        CidrBlock.parse(rule.peer()), rule.portStart(), rule.portEnd()));
            } catch (RuntimeException invalid) {
                throw ApiException.validationFailed(List.of(new FieldValidationError(
                        "rules[" + index + "]", invalid.getMessage())));
            }
        }
        return List.copyOf(result);
    }

    private static VmNetworkPolicyView toView(VmNetworkPolicyStore.Snapshot policy) {
        List<VmNetworkPolicyRuleView> rules = policy.rules().stream()
                .map(rule -> new VmNetworkPolicyRuleView(rule.direction(), rule.action(),
                        rule.protocol(), rule.peer().toString(), rule.portStart(), rule.portEnd()))
                .toList();
        return new VmNetworkPolicyView(policy.revision(), rules, SYSTEM_RULES,
                policy.state(), policy.desiredGeneration(), policy.appliedGeneration(),
                policy.lastError(), policy.updatedAt());
    }

    private static Map<String, Object> auditDetail(long previousRevision,
            VmNetworkPolicyStore.Snapshot saved, boolean adminIntervention) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("previousRevision", previousRevision);
        detail.put("revision", saved.revision());
        detail.put("rules", saved.rules().stream().map(rule -> Map.of(
                "direction", rule.direction().name(), "action", rule.action().name(),
                "protocol", rule.protocol().name(), "peer", rule.peer().toString(),
                "portStart", rule.portStart() == null ? "" : rule.portStart(),
                "portEnd", rule.portEnd() == null ? "" : rule.portEnd())).toList());
        detail.put("adminIntervention", adminIntervention);
        return detail;
    }
}
