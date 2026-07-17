package kr.ac.pusan.pickle.vm;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.crypto.CredentialCipher;
import kr.ac.pusan.pickle.common.crypto.VmPasswordGenerator;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.proxmox.ProxmoxApiException;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.dto.InitialPasswordResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VM guest-password reveal and reset (contract ops {@code revealInitialPassword}
 * / {@code resetVmPassword}, docs/plan/03 initial credentials).
 *
 * <p>Since the 2026-07-17 policy change (product-spec §8) the password is
 * stored as a reversible AES-GCM ciphertext instead of one-shot plaintext:
 * {@link #reveal} decrypts and returns it any number of times (recording the
 * reveal time), and {@link #reset} mints a fresh 24-char password, applies it
 * in-guest through the QEMU guest agent (synchronous, no reboot) and stores
 * the new ciphertext + BCrypt hash.</p>
 *
 * <p>The plaintext exists only in the HTTP response: reveals and resets are
 * recorded in {@code audit_logs} as the bare fact ({@code vm.password_reveal}
 * / {@code vm.password_reset}), never the value, and nothing is logged or
 * written to vm_events.</p>
 */
@Service
public class InitialPasswordService {

    private static final Set<VmStatus> FORBIDDEN_STATUSES = Set.of(VmStatus.CREATING,
            VmStatus.DELETING, VmStatus.DELETED, VmStatus.ERROR, VmStatus.NEEDS_ADMIN);

    private final VmRepository vmRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final NodeRepository nodeRepository;
    private final ProxmoxClient proxmox;
    private final CredentialCipher credentialCipher;
    private final VmPasswordGenerator passwordGenerator;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final String sshHost;
    private final Integer sshPort;

    public InitialPasswordService(VmRepository vmRepository,
            GroupMemberRepository groupMemberRepository, NodeRepository nodeRepository,
            ProxmoxClient proxmox, CredentialCipher credentialCipher,
            VmPasswordGenerator passwordGenerator, PasswordEncoder passwordEncoder,
            AuditService auditService,
            @Value("${pickle.ssh.advertised-host:}") String sshHost,
            @Value("${pickle.ssh.advertised-port:0}") int sshPort) {
        this.vmRepository = vmRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.nodeRepository = nodeRepository;
        this.proxmox = proxmox;
        this.credentialCipher = credentialCipher;
        this.passwordGenerator = passwordGenerator;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.sshHost = sshHost == null || sshHost.isBlank() ? null : sshHost;
        this.sshPort = sshPort <= 0 ? null : sshPort;
    }

    @Transactional
    public InitialPasswordResponse reveal(AuthenticatedUser actor, long vmId, String ip) {
        Vm vm = requireMemberVm(actor, vmId, "열람");
        if (FORBIDDEN_STATUSES.contains(vm.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                    "현재 상태에서는 수행할 수 없는 작업입니다",
                    "VM 생성이 완료된 뒤에 초기 비밀번호를 열람할 수 있습니다. (현재 상태 "
                            + vm.getStatus() + ")");
        }
        if (vm.getInitialPasswordEnc() == null) {
            // Mock-provisioned VMs, or rows whose plaintext was destroyed under
            // the pre-v0.7.0 one-shot policy. The code value stays for
            // backwards compatibility.
            throw new ApiException(HttpStatus.GONE, ErrorCodes.VM_PASSWORD_ALREADY_VIEWED,
                    "초기 비밀번호를 열람할 수 없습니다",
                    "저장된 초기 비밀번호가 없습니다. 비밀번호가 필요하면 비밀번호 재설정을 이용해 주세요.");
        }
        String password = credentialCipher.decrypt(vm.getInitialPasswordEnc());
        vmRepository.recordInitialPasswordViewed(vmId, Instant.now());
        auditService.record(actor.id(), actor.role().name(), AuditService.VM_PASSWORD_REVEAL,
                "vm", vmId, Map.of(), ip);
        return new InitialPasswordResponse(password, vm.getSshUsername(), sshHost, sshPort);
    }

    /**
     * Mints a fresh password and applies it in-guest via the QEMU guest agent.
     * The DB is updated only after the agent call succeeds, so the stored
     * value never gets ahead of the guest.
     */
    @Transactional
    public InitialPasswordResponse reset(AuthenticatedUser actor, long vmId, String ip) {
        Vm vm = requireMemberVm(actor, vmId, "재설정");
        if (vm.getStatus() != VmStatus.RUNNING || vm.getProxmoxVmid() == null) {
            throw agentUnavailable();
        }
        Node node = nodeRepository.findById(vm.getNodeId()).orElseThrow();
        String password = passwordGenerator.generate();
        try {
            proxmox.agentSetUserPassword(node.getApiHost(), node.getName(), vm.getProxmoxVmid(),
                    vm.getSshUsername(), password);
        } catch (ProxmoxApiException e) {
            // "QEMU guest agent is not running" (500) → user-actionable 409;
            // anything else is a genuine upstream failure.
            if (e.getMessage() != null && e.getMessage().contains("guest agent is not running")) {
                throw agentUnavailable();
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.PROXMOX_UPSTREAM_ERROR,
                    "하이퍼바이저 요청에 실패했습니다",
                    "잠시 후 다시 시도해 주세요. 문제가 계속되면 관리자에게 문의하세요.");
        }
        vmRepository.storeInitialCredentials(vmId, credentialCipher.encrypt(password),
                passwordEncoder.encode(password), Instant.now());
        auditService.record(actor.id(), actor.role().name(), AuditService.VM_PASSWORD_RESET,
                "vm", vmId, Map.of(), ip);
        return new InitialPasswordResponse(password, vm.getSshUsername(), sshHost, sshPort);
    }

    private static ApiException agentUnavailable() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                "현재 상태에서는 수행할 수 없는 작업입니다",
                "VM이 실행 중이고 게스트 에이전트가 응답할 때만 비밀번호를 재설정할 수 있습니다.");
    }

    /** MEMBER+ only: non-member answers 404 (masking), VIEWER answers 403. */
    private Vm requireMemberVm(AuthenticatedUser actor, long vmId, String actionLabel) {
        Vm vm = vmRepository.findById(vmId)
                .orElseThrow(InitialPasswordService::vmNotFound);
        GroupMemberRole role = groupMemberRepository
                .findByGroupIdAndUserId(vm.getGroupId(), actor.id())
                .map(GroupMember::getRole)
                .orElseThrow(InitialPasswordService::vmNotFound);
        if (role == GroupMemberRole.VIEWER) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.GROUP_ROLE_INSUFFICIENT,
                    "비밀번호를 " + actionLabel + "할 권한이 없습니다",
                    "그룹의 MEMBER 이상만 비밀번호를 " + actionLabel + "할 수 있습니다.");
        }
        return vm;
    }

    private static ApiException vmNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 VM이 존재하지 않습니다.");
    }
}
