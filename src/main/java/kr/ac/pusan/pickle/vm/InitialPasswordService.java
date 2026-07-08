package kr.ac.pusan.pickle.vm;

import java.util.Map;
import java.util.Set;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.dto.InitialPasswordResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-shot initial-password reveal (contract op {@code revealInitialPassword},
 * docs/plan/03 initial credentials): returns the plaintext exactly once and
 * destroys it (only the BCrypt hash column survives for support verification).
 *
 * <p>Exactly-once under concurrency comes from a single atomic consume
 * statement: the CTE UPDATE nulls the column guarded by {@code IS NOT NULL}
 * (the row lock serializes racers; the loser re-evaluates the guard against
 * the committed NULL and consumes nothing), while the outer SELECT still sees
 * the statement snapshot and thus the pre-update plaintext for the winner.</p>
 *
 * <p>The plaintext exists only in the HTTP response: the reveal is recorded in
 * {@code audit_logs} as the bare fact ({@code vm.password.reveal}), never the
 * value, and nothing is logged or written to vm_events.</p>
 */
@Service
public class InitialPasswordService {

    private static final Set<VmStatus> FORBIDDEN_STATUSES = Set.of(VmStatus.CREATING,
            VmStatus.DELETING, VmStatus.DELETED, VmStatus.ERROR, VmStatus.NEEDS_ADMIN);

    private final VmRepository vmRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;
    private final String sshHost;
    private final Integer sshPort;

    public InitialPasswordService(VmRepository vmRepository,
            GroupMemberRepository groupMemberRepository, JdbcTemplate jdbcTemplate,
            AuditService auditService,
            @Value("${pickle.ssh.advertised-host:}") String sshHost,
            @Value("${pickle.ssh.advertised-port:0}") int sshPort) {
        this.vmRepository = vmRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
        this.sshHost = sshHost == null || sshHost.isBlank() ? null : sshHost;
        this.sshPort = sshPort <= 0 ? null : sshPort;
    }

    @Transactional
    public InitialPasswordResponse reveal(AuthenticatedUser actor, long vmId, String ip) {
        Vm vm = requireMemberVm(actor, vmId);
        if (FORBIDDEN_STATUSES.contains(vm.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                    "현재 상태에서는 수행할 수 없는 작업입니다",
                    "VM 생성이 완료된 뒤에 초기 비밀번호를 열람할 수 있습니다. (현재 상태 "
                            + vm.getStatus() + ")");
        }
        String password = jdbcTemplate.query("""
                with consumed as (
                    update vms
                       set initial_password = null, initial_password_viewed_at = now()
                     where id = ? and initial_password is not null
                     returning id
                )
                select initial_password from vms where id in (select id from consumed)
                """, rs -> rs.next() ? rs.getString(1) : null, vmId);
        if (password == null) {
            throw new ApiException(HttpStatus.GONE, ErrorCodes.VM_PASSWORD_ALREADY_VIEWED,
                    "초기 비밀번호를 열람할 수 없습니다",
                    "초기 비밀번호가 이미 열람되었거나 존재하지 않습니다. "
                            + "비밀번호가 필요하면 비밀번호 재설정을 이용해 주세요.");
        }
        auditService.record(actor.id(), actor.role().name(), AuditService.VM_PASSWORD_REVEAL,
                "vm", vmId, Map.of(), ip);
        return new InitialPasswordResponse(password, vm.getSshUsername(), sshHost, sshPort);
    }

    /** MEMBER+ only: non-member answers 404 (masking), VIEWER answers 403. */
    private Vm requireMemberVm(AuthenticatedUser actor, long vmId) {
        Vm vm = vmRepository.findById(vmId)
                .orElseThrow(InitialPasswordService::vmNotFound);
        GroupMemberRole role = groupMemberRepository
                .findByGroupIdAndUserId(vm.getGroupId(), actor.id())
                .map(GroupMember::getRole)
                .orElseThrow(InitialPasswordService::vmNotFound);
        if (role == GroupMemberRole.VIEWER) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.GROUP_ROLE_INSUFFICIENT,
                    "초기 비밀번호를 열람할 권한이 없습니다",
                    "그룹의 MEMBER 이상만 초기 비밀번호를 열람할 수 있습니다.");
        }
        return vm;
    }

    private static ApiException vmNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 VM이 존재하지 않습니다.");
    }
}
