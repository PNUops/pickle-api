package kr.ac.pusan.pickle.campusip;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import kr.ac.pusan.pickle.access.VmAccessService;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.campusip.dto.AdminCampusIpRequestView;
import kr.ac.pusan.pickle.campusip.dto.CampusIpRequestView;
import kr.ac.pusan.pickle.campusip.dto.CreateCampusIpRequest;
import kr.ac.pusan.pickle.campusip.dto.UpdateCampusIpRequestStatusRequest;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 교내 IP allocation requests (contract tag {@code campus-ip}). The request is
 * pure workflow state — nothing is provisioned automatically; a platform
 * administrator walks it through REQUESTED → APPROVED → GRANTED (recording
 * the campus address once the VM is wired to it) or rejects/revokes.
 *
 * <p>User-side authorization is service-layer group scoping only, exactly
 * like port forwarding and publishing: reads need membership, writes need
 * OWNER/EDITOR, and a non-member gets the 404 existence mask. Cross-group
 * intervention lives on the admin surface.</p>
 */
@Service
public class CampusIpRequestService {

    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

    /** The campus range (10.0.0.0/8) a granted address must fall inside. */
    private static final String CAMPUS_PREFIX = "10.";

    private static final Set<CampusIpRequestStatus> LIVE_STATUSES = Set.of(
            CampusIpRequestStatus.REQUESTED, CampusIpRequestStatus.APPROVED,
            CampusIpRequestStatus.GRANTED);

    private final CampusIpRequestRepository requestRepository;
    private final VmRepository vmRepository;
    private final VmAccessService vmAccessService;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public CampusIpRequestService(CampusIpRequestRepository requestRepository,
            VmRepository vmRepository, VmAccessService vmAccessService,
            UserRepository userRepository, NotificationService notificationService,
            AuditService auditService, ObjectMapper objectMapper) {
        this.requestRepository = requestRepository;
        this.vmRepository = vmRepository;
        this.vmAccessService = vmAccessService;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    // ── user ops ─────────────────────────────────────────────────────────────

    /** Reads need membership only (VIEWER+); writes need OWNER/EDITOR. */
    @Transactional(readOnly = true)
    public List<CampusIpRequestView> list(AuthenticatedUser actor, long vmId) {
        requireVmMember(actor, vmId);
        return requestRepository.findByVmIdOrderByIdDesc(vmId).stream()
                .map(this::toView).toList();
    }

    @Transactional
    public CampusIpRequestView create(AuthenticatedUser actor, long vmId,
            CreateCampusIpRequest request, String ip) {
        Vm vm = requireVmOwnerOrEditor(actor, vmId);
        String purpose = request.purpose().strip();
        List<Integer> ports = normalizePorts(request.ports());
        if (requestRepository.existsByVmIdAndStatusIn(vmId, LIVE_STATUSES)) {
            throw liveRequestExists();
        }
        CampusIpRequest created;
        try {
            created = requestRepository.saveAndFlush(new CampusIpRequest(vmId, actor.id(),
                    purpose, objectMapper.writeValueAsString(ports)));
        } catch (DataIntegrityViolationException raced) {
            // The partial unique index is the arbiter under a concurrent
            // create — the loser gets the same 409 as the pre-check.
            throw liveRequestExists();
        }
        notificationService.publish(notificationService.sysAdminIds(),
                NotificationEvent.CAMPUS_IP_REQUESTED,
                Map.of("requestId", created.getId(), "vmId", vmId, "vmName", vm.getName(),
                        "purpose", purpose),
                null);
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.CAMPUS_IP_REQUEST, "campus_ip_request", created.getId(),
                Map.of("vmId", vmId, "ports", ports), ip);
        return toView(created);
    }

    /**
     * Cancel = remove the row (204, pure DB state — nothing to converge);
     * only a still-unreviewed (REQUESTED) 신청.
     */
    @Transactional
    public void cancel(AuthenticatedUser actor, long vmId, long requestId, String ip) {
        requireVmOwnerOrEditor(actor, vmId);
        CampusIpRequest request = requestRepository.findByIdAndVmId(requestId, vmId)
                .orElseThrow(CampusIpRequestService::requestNotFound);
        if (request.getStatus() != CampusIpRequestStatus.REQUESTED) {
            throw invalidTransition("검토가 시작되기 전(REQUESTED)의 신청만 취소할 수 있습니다.");
        }
        requestRepository.delete(request);
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.CAMPUS_IP_CANCEL, "campus_ip_request", requestId,
                Map.of("vmId", vmId), ip);
    }

    // ── admin ops ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<AdminCampusIpRequestView> adminList(CampusIpRequestStatus status,
            Long vmId, int page, int size) {
        Specification<CampusIpRequest> spec = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (vmId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("vmId"), vmId));
        }
        Page<CampusIpRequest> result = requestRepository.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        List<AdminCampusIpRequestView> views = result.getContent().stream().map(request -> {
            Vm vm = vmRepository.findById(request.getVmId()).orElse(null);
            User requester = userRepository.findById(request.getRequestedBy()).orElse(null);
            return new AdminCampusIpRequestView(request.getId(), request.getVmId(),
                    vm != null ? vm.getName() : null, vm != null ? vm.getOrgId() : null,
                    request.getPurpose(), parsePorts(request.getPorts()), request.getStatus(),
                    request.getGrantedAddress(), request.getAdminNote(),
                    request.getRequestedBy(),
                    requester != null ? requester.getEmail() : null,
                    request.getProcessedBy(), request.getProcessedAt(), request.getCreatedAt());
        }).toList();
        return PageResponse.of(views, result);
    }

    @Transactional
    public AdminCampusIpRequestView updateStatus(AuthenticatedUser actor, long requestId,
            UpdateCampusIpRequestStatusRequest body, String ip) {
        CampusIpRequest request = requestRepository.findById(requestId)
                .orElseThrow(CampusIpRequestService::requestNotFound);
        CampusIpRequestStatus from = request.getStatus();
        CampusIpRequestStatus to = body.status();
        if (!isLegalTransition(from, to)) {
            throw invalidTransition("'" + from + "'에서 '" + to + "'(으)로 전환할 수 없습니다.");
        }
        if (to == CampusIpRequestStatus.GRANTED) {
            String address = Texts.blankToNull(body.grantedAddress());
            if (address == null || !IPV4.matcher(address).matches()) {
                throw ApiException.validationFailed(List.of(new FieldValidationError(
                        "grantedAddress", "GRANTED 전환에는 올바른 IPv4 주소가 필요합니다.")));
            }
            // Campus addresses live in 10.0.0.0/8; anything else is a typo or
            // a wrong network, and recording it would advertise an address the
            // VM is not actually reachable at.
            if (!address.startsWith(CAMPUS_PREFIX)) {
                throw ApiException.validationFailed(List.of(new FieldValidationError(
                        "grantedAddress", "교내 IP는 10.0.0.0/8 대역의 주소여야 합니다.")));
            }
            request.setGrantedAddress(address);
        }
        request.setStatus(to);
        request.setAdminNote(Texts.blankToNull(body.adminNote()));
        request.setProcessedBy(actor.id());
        request.setProcessedAt(Instant.now());

        Vm vm = vmRepository.findById(request.getVmId()).orElse(null);
        notificationService.publish(request.getRequestedBy(),
                NotificationEvent.CAMPUS_IP_STATUS_CHANGED,
                notificationArgs(request, vm), null);
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.CAMPUS_IP_STATUS_UPDATE, "campus_ip_request", requestId,
                Map.of("vmId", request.getVmId(), "from", from.name(), "to", to.name()), ip);
        User requester = userRepository.findById(request.getRequestedBy()).orElse(null);
        return new AdminCampusIpRequestView(request.getId(), request.getVmId(),
                vm != null ? vm.getName() : null, vm != null ? vm.getOrgId() : null,
                request.getPurpose(), parsePorts(request.getPorts()), request.getStatus(),
                request.getGrantedAddress(), request.getAdminNote(), request.getRequestedBy(),
                requester != null ? requester.getEmail() : null, request.getProcessedBy(),
                request.getProcessedAt(), request.getCreatedAt());
    }

    /** REQUESTED → APPROVED|REJECTED, APPROVED → GRANTED|REJECTED, GRANTED → REVOKED. */
    static boolean isLegalTransition(CampusIpRequestStatus from, CampusIpRequestStatus to) {
        return switch (from) {
            case REQUESTED -> to == CampusIpRequestStatus.APPROVED
                    || to == CampusIpRequestStatus.REJECTED;
            case APPROVED -> to == CampusIpRequestStatus.GRANTED
                    || to == CampusIpRequestStatus.REJECTED;
            case GRANTED -> to == CampusIpRequestStatus.REVOKED;
            case REJECTED, REVOKED -> false;
        };
    }

    private Map<String, Object> notificationArgs(CampusIpRequest request, Vm vm) {
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("requestId", request.getId());
        args.put("vmId", request.getVmId());
        args.put("vmName", vm != null ? vm.getName() : "");
        args.put("statusLabel", statusLabel(request.getStatus()));
        if (request.getGrantedAddress() != null
                && request.getStatus() == CampusIpRequestStatus.GRANTED) {
            args.put("grantedAddress", request.getGrantedAddress());
        }
        if (request.getAdminNote() != null) {
            args.put("adminNote", request.getAdminNote());
        }
        return args;
    }

    private static String statusLabel(CampusIpRequestStatus status) {
        return switch (status) {
            case REQUESTED -> "신청됨";
            case APPROVED -> "승인";
            case GRANTED -> "IP 할당 완료";
            case REJECTED -> "반려";
            case REVOKED -> "회수";
        };
    }

    // ── validation / helpers ─────────────────────────────────────────────────

    /** 1–65535 each, deduped and sorted; ≤32 items after dedup. */
    private static List<Integer> normalizePorts(List<Integer> ports) {
        List<FieldValidationError> errors = new ArrayList<>();
        TreeSet<Integer> normalized = new TreeSet<>();
        for (int i = 0; i < ports.size(); i++) {
            Integer port = ports.get(i);
            if (port == null || port < 1 || port > 65535) {
                errors.add(new FieldValidationError("ports[" + i + "]",
                        "포트는 1~65535 범위여야 합니다."));
            } else {
                normalized.add(port);
            }
        }
        if (normalized.size() > 32) {
            errors.add(new FieldValidationError("ports", "포트는 최대 32개까지 신청할 수 있습니다."));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
        return List.copyOf(normalized);
    }

    private CampusIpRequestView toView(CampusIpRequest request) {
        return new CampusIpRequestView(request.getId(), request.getVmId(), request.getPurpose(),
                parsePorts(request.getPorts()), request.getStatus(), request.getGrantedAddress(),
                request.getAdminNote(), request.getRequestedBy(), request.getProcessedAt(),
                request.getCreatedAt());
    }

    private List<Integer> parsePorts(String json) {
        JsonNode node = objectMapper.readTree(json);
        List<Integer> ports = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> ports.add(item.asInt()));
        }
        return List.copyOf(ports);
    }

    /** Membership check (VIEWER+): non-members get the 404 existence mask. */
    private Vm requireVmMember(AuthenticatedUser actor, long vmId) {
        return vmAccessService.of(actor, vmId).requireVisible();
    }

    private Vm requireVmOwnerOrEditor(AuthenticatedUser actor, long vmId) {
        return vmAccessService.of(actor, vmId).requireAtLeast(GroupMemberRole.EDITOR,
                "교내 IP를 신청할 권한이 없습니다",
                "그룹 소유자(OWNER) 또는 편집자(EDITOR)만 교내 IP를 신청할 수 있습니다.");
    }

    private static ApiException liveRequestExists() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.CAMPUS_IP_REQUEST_EXISTS,
                "이미 진행 중인 신청이 있습니다",
                "이 VM에는 진행 중인 교내 IP 신청이 이미 있습니다. 기존 신청이 끝난 뒤 다시 신청해 주세요.");
    }

    private static ApiException invalidTransition(String detail) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.CAMPUS_IP_INVALID_TRANSITION,
                "전환할 수 없는 상태입니다", detail);
    }

    private static ApiException requestNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 교내 IP 신청이 존재하지 않습니다.");
    }
}
