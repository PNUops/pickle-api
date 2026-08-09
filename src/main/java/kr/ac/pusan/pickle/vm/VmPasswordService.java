package kr.ac.pusan.pickle.vm;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.VmAccess;
import kr.ac.pusan.pickle.access.VmAccessService;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.crypto.CredentialCipher;
import kr.ac.pusan.pickle.common.crypto.VmPasswordGenerator;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.dto.VmPasswordResponse;
import kr.ac.pusan.pickle.vmsettings.VmSettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VM guest-password reveal and regeneration (contract ops {@code revealVmPassword},
 * {@code regenerateVmPassword}). The password is stored as a reversible AES-GCM
 * ciphertext, so reveal decrypts it any number of times (each reveal audited).
 *
 * <p>In the key-login world the password is not a login method but the VM's
 * <b>in-VM sudo credential</b>, so the reveal minimum role is governed per-VM by
 * {@code password_reveal_min_role} (default MEMBER). Regeneration mints a fresh
 * 24-char CSPRNG password and applies it live via the guest agent
 * ({@code set-user-password}), immediately invalidating the old one — the way to
 * revoke a departed member's shared-password access.</p>
 *
 * <p>The plaintext exists only in the HTTP response; audits record the fact
 * ({@code vm.password_reveal} / {@code vm.password_regenerate}), never the value.</p>
 */
@Service
public class VmPasswordService {

    private static final Set<VmStatus> FORBIDDEN_REVEAL_STATUSES = Set.of(VmStatus.CREATING,
            VmStatus.DELETING, VmStatus.DELETED, VmStatus.ERROR, VmStatus.NEEDS_ADMIN);

    private final VmRepository vmRepository;
    private final VmAccessService vmAccessService;
    private final VmSettingsService vmSettingsService;
    private final CredentialCipher credentialCipher;
    private final VmPasswordGenerator passwordGenerator;
    private final PasswordEncoder passwordEncoder;
    private final NodeRepository nodeRepository;
    private final ProxmoxClient proxmox;
    private final AuditService auditService;
    private final String sshHost;
    private final Integer sshPort;

    public VmPasswordService(VmRepository vmRepository,
            VmAccessService vmAccessService, VmSettingsService vmSettingsService,
            CredentialCipher credentialCipher, VmPasswordGenerator passwordGenerator,
            PasswordEncoder passwordEncoder, NodeRepository nodeRepository, ProxmoxClient proxmox,
            AuditService auditService,
            @Value("${pickle.ssh.advertised-host:}") String sshHost,
            @Value("${pickle.ssh.advertised-port:0}") int sshPort) {
        this.vmRepository = vmRepository;
        this.vmAccessService = vmAccessService;
        this.vmSettingsService = vmSettingsService;
        this.credentialCipher = credentialCipher;
        this.passwordGenerator = passwordGenerator;
        this.passwordEncoder = passwordEncoder;
        this.nodeRepository = nodeRepository;
        this.proxmox = proxmox;
        this.auditService = auditService;
        this.sshHost = sshHost == null || sshHost.isBlank() ? null : sshHost;
        this.sshPort = sshPort <= 0 ? null : sshPort;
    }

    /** Reveals the stored password; min role is per-VM {@code password_reveal_min_role}. */
    @Transactional
    public VmPasswordResponse reveal(AuthenticatedUser actor, long vmId, String ip) {
        MemberVm memberVm = requireMemberVm(actor, vmId);
        Vm vm = memberVm.vm();
        ResourceRole minRole =
                vmSettingsService.role(vmId, VmSettingsService.PASSWORD_REVEAL_MIN_ROLE);
        if (!memberVm.role().atLeast(minRole)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.WORKSPACE_ROLE_INSUFFICIENT,
                    "비밀번호를 열람할 권한이 없습니다",
                    "이 VM은 접근 권한 " + minRole + " 이상만 비밀번호를 열람할 수 있습니다.");
        }
        if (FORBIDDEN_REVEAL_STATUSES.contains(vm.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                    "현재 상태에서는 수행할 수 없는 작업입니다",
                    "VM 생성이 완료된 뒤에 비밀번호를 열람할 수 있습니다. (현재 상태 " + vm.getStatus() + ")");
        }
        if (vm.getPasswordEnc() == null) {
            // Mock-provisioned VMs, or rows whose plaintext predates the stored
            // ciphertext. The code value stays for backwards compatibility.
            throw new ApiException(HttpStatus.GONE, ErrorCodes.VM_PASSWORD_ALREADY_VIEWED,
                    "비밀번호를 열람할 수 없습니다",
                    "저장된 비밀번호가 없습니다. 비밀번호 재생성으로 새 비밀번호를 만들 수 있습니다.");
        }
        String password = credentialCipher.decrypt(vm.getPasswordEnc());
        vmRepository.recordPasswordViewed(vmId, Instant.now());
        auditService.record(actor.id(), actor.role().name(), AuditService.VM_PASSWORD_REVEAL,
                "vm", vmId, Map.of(), ip);
        return new VmPasswordResponse(password, vm.getSshUsername(), sshHost, sshPort);
    }

    /**
     * Regenerates the password (EDITOR+): a fresh CSPRNG value applied live via
     * the guest agent, replacing the stored ciphertext. Requires a RUNNING VM
     * whose agent answers — otherwise 409. Nobody (admins included) can choose
     * the value; generation is always the platform's.
     */
    @Transactional
    public VmPasswordResponse regenerate(AuthenticatedUser actor, long vmId, String ip) {
        MemberVm memberVm = requireMemberVm(actor, vmId);
        Vm vm = memberVm.vm();
        if (!memberVm.role().atLeast(ResourceRole.EDITOR)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.WORKSPACE_ROLE_INSUFFICIENT,
                    "비밀번호를 재생성할 권한이 없습니다",
                    "이 VM의 편집자 이상만 비밀번호를 재생성할 수 있습니다.");
        }
        if (vm.getStatus() != VmStatus.RUNNING || vm.getProxmoxVmid() == null) {
            throw agentUnavailable();
        }
        Node node = nodeRepository.findById(vm.getNodeId()).orElseThrow(
                () -> new IllegalStateException("VM " + vmId + "의 노드를 찾을 수 없습니다"));
        String password = passwordGenerator.generate();
        // Guest-agent HTTP failure (agent not running) → false → 409; a transport
        // failure propagates as 5xx (infrastructure, not a VM-state problem).
        boolean applied = proxmox.agentSetUserPassword(node.getApiHost(), node.getName(),
                vm.getProxmoxVmid(), vm.getSshUsername(), password);
        if (!applied) {
            throw agentUnavailable();
        }
        vmRepository.storeCredentials(vmId, credentialCipher.encrypt(password),
                passwordEncoder.encode(password), Instant.now());
        auditService.record(actor.id(), actor.role().name(), AuditService.VM_PASSWORD_REGENERATE,
                "vm", vmId, Map.of(), ip);
        return new VmPasswordResponse(password, vm.getSshUsername(), sshHost, sshPort);
    }

    private record MemberVm(Vm vm, ResourceRole role) {
    }

    /** Non-member answers 404 (masking); returns the VM and the actor's role. */
    private MemberVm requireMemberVm(AuthenticatedUser actor, long vmId) {
        VmAccess access = vmAccessService.of(actor, vmId);
        return new MemberVm(access.requireVisible(), access.role());
    }

    private static ApiException agentUnavailable() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                "현재 상태에서는 수행할 수 없는 작업입니다",
                "VM이 실행 중이고 게스트 에이전트가 응답할 때만 비밀번호를 재생성할 수 있습니다.");
    }
}
