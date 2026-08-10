package kr.ac.pusan.pickle.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.admin.dto.DriftFindingResponse;
import kr.ac.pusan.pickle.admin.dto.ResolveDriftFindingRequest;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.provisioning.DriftFinding;
import kr.ac.pusan.pickle.provisioning.DriftFindingKind;
import kr.ac.pusan.pickle.provisioning.DriftFindingRepository;
import kr.ac.pusan.pickle.provisioning.DriftFindingStatus;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SYS_ADMIN drift report: list persisted findings and resolve them manually.
 * All writes are CAS (0 rows = already resolved → 409) so double-clicks and
 * a concurrent reconciler auto-resolve stay race-free.
 */
@Service
public class AdminDriftService {

    private final DriftFindingRepository driftFindingRepository;
    private final UserRepository userRepository;
    private final VmRepository vmRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AdminDriftService(DriftFindingRepository driftFindingRepository,
            UserRepository userRepository, VmRepository vmRepository, AuditService auditService,
            ObjectMapper objectMapper) {
        this.driftFindingRepository = driftFindingRepository;
        this.userRepository = userRepository;
        this.vmRepository = vmRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** Contract {@code listDriftFindings}: status defaults to OPEN, newest observation first. */
    public PageResponse<DriftFindingResponse> list(DriftFindingStatus status, DriftFindingKind kind,
            int page, int size) {
        DriftFindingStatus effectiveStatus = status == null ? DriftFindingStatus.OPEN : status;
        Specification<DriftFinding> spec =
                (root, query, cb) -> cb.equal(root.get("status"), effectiveStatus);
        if (kind != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("kind"), kind));
        }
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("lastSeenAt"), Sort.Order.desc("id")));
        Page<DriftFinding> result = driftFindingRepository.findAll(spec, pageable);
        Map<Long, User> resolvers = resolversById(result.getContent());
        Map<Long, Vm> vms = vmsById(result.getContent());
        List<DriftFindingResponse> content = result.getContent().stream()
                .map(finding -> {
                    Vm vm = finding.getVmId() == null ? null : vms.get(finding.getVmId());
                    User resolver = finding.getResolvedBy() == null ? null
                            : resolvers.get(finding.getResolvedBy());
                    return toResponse(finding, vm == null ? null : vm.getPublicId(),
                            vm == null ? null : vm.getName(),
                            resolver == null ? null : resolver.getPublicId(),
                            resolver == null ? null : resolver.getEmail());
                })
                .toList();
        return PageResponse.of(content, result);
    }

    /** Contract {@code resolveDriftFinding}: CAS OPEN→RESOLVED with the acting admin. */
    @Transactional
    public DriftFindingResponse resolve(AuthenticatedUser actor, UUID publicFindingId,
            ResolveDriftFindingRequest request, String ip) {
        String note = request == null ? null : request.note();
        long findingId = driftFindingRepository.findByPublicId(publicFindingId)
                .map(DriftFinding::getId).orElse(-1L);
        if (driftFindingRepository.resolve(findingId, actor.id(), note, Instant.now()) == 0) {
            driftFindingRepository.findById(findingId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            ErrorCodes.RESOURCE_NOT_FOUND, "리소스를 찾을 수 없습니다",
                            "드리프트 발견을 찾을 수 없습니다."));
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DRIFT_FINDING_ALREADY_RESOLVED,
                    "이미 해결된 발견입니다", "이 드리프트 발견은 이미 해결 처리되었습니다.");
        }
        DriftFinding finding = driftFindingRepository.findById(findingId).orElseThrow();
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.DRIFT_RESOLVE,
                "drift_finding", finding.getPublicId(),
                note == null ? Map.of("kind", finding.getKind().name())
                        : Map.of("kind", finding.getKind().name(), "note", note),
                ip);
        String vmName = finding.getVmId() == null ? null
                : vmRepository.findById(finding.getVmId()).map(Vm::getName).orElse(null);
        return toResponse(finding, vmPublicId(finding.getVmId()), vmName,
                actor.publicId(), actor.email());
    }

    private Map<Long, User> resolversById(List<DriftFinding> findings) {
        List<Long> ids = findings.stream()
                .map(DriftFinding::getResolvedBy)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return ids.isEmpty() ? Map.of()
                : userRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(User::getId, java.util.function.Function.identity()));
    }

    /** VM-name join (v0.9.0 display field); null vmId (UNMANAGED_GUEST) → no entry. */
    private Map<Long, Vm> vmsById(List<DriftFinding> findings) {
        List<Long> ids = findings.stream()
                .map(DriftFinding::getVmId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return ids.isEmpty() ? Map.of()
                : vmRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(Vm::getId, java.util.function.Function.identity()));
    }

    private DriftFindingResponse toResponse(DriftFinding finding, UUID vmId, String vmName,
            UUID resolvedById, String resolvedByEmail) {
        JsonNode detail = finding.getDetail() == null ? null
                : objectMapper.readTree(finding.getDetail());
        return DriftFindingResponse.from(finding, detail, vmId, vmName, resolvedById, resolvedByEmail);
    }

    private UUID vmPublicId(Long vmId) {
        return vmId == null ? null
                : vmRepository.findById(vmId).map(Vm::getPublicId).orElse(null);
    }
}
