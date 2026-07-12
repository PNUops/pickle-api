package kr.ac.pusan.pickle.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AdminDriftService(DriftFindingRepository driftFindingRepository,
            UserRepository userRepository, AuditService auditService, ObjectMapper objectMapper) {
        this.driftFindingRepository = driftFindingRepository;
        this.userRepository = userRepository;
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
        Map<Long, String> emails = emailsById(result.getContent());
        List<DriftFindingResponse> content = result.getContent().stream()
                .map(finding -> toResponse(finding, finding.getResolvedBy() == null ? null
                        : emails.get(finding.getResolvedBy())))
                .toList();
        return PageResponse.of(content, result);
    }

    /** Contract {@code resolveDriftFinding}: CAS OPEN→RESOLVED with the acting admin. */
    @Transactional
    public DriftFindingResponse resolve(AuthenticatedUser actor, long findingId,
            ResolveDriftFindingRequest request, String ip) {
        String note = request == null ? null : request.note();
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
                "drift_finding", findingId,
                note == null ? Map.of("kind", finding.getKind().name())
                        : Map.of("kind", finding.getKind().name(), "note", note),
                ip);
        return toResponse(finding, actor.email());
    }

    private Map<Long, String> emailsById(List<DriftFinding> findings) {
        List<Long> ids = findings.stream()
                .map(DriftFinding::getResolvedBy)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return ids.isEmpty() ? Map.of()
                : userRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(User::getId, User::getEmail));
    }

    private DriftFindingResponse toResponse(DriftFinding finding, String resolvedByEmail) {
        JsonNode detail = finding.getDetail() == null ? null
                : objectMapper.readTree(finding.getDetail());
        return DriftFindingResponse.from(finding, detail, resolvedByEmail);
    }
}
