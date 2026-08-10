package kr.ac.pusan.pickle.relay;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.relay.dto.AdminPortMappingResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmEvent;
import kr.ac.pusan.pickle.vm.VmEventRepository;
import kr.ac.pusan.pickle.vm.VmEventType;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Admin port-mapping intervention surface (contract tag {@code admin}):
 * list, suspend/unsuspend, delete and per-mapping guard overrides. Every
 * write bumps the owning relay's generation in the same transaction, so the
 * agent converges on the next sync.
 */
@Service
public class AdminPortMappingService {

    /** Guard columns the PATCH accepts, in the documented order. */
    static final List<String> GUARD_FIELDS = List.of(
            "ctMax", "newConnRate", "newConnBurst", "perSourceRate", "perSourceBurst");

    /**
     * Upper bound accepted for any guard value. Every guard is rendered into a
     * real packet-filter rule on the relay (a connection ceiling, a per-second
     * rate, or the burst that goes with a rate), and a value the kernel refuses
     * fails the WHOLE table apply: the relay then freezes at its last
     * generation, and the agent's error item for a table-level failure carries
     * no mapping id, so nothing in the console points at the offending row.
     * One million concurrent connections, one million new connections per
     * second, or a burst of a million is already orders of magnitude past what
     * a single VM behind one forwarded port can serve, so anything larger is an
     * operator typo and is better refused at the edit than shipped to the
     * relay.
     */
    static final int GUARD_VALUE_MAX = 1_000_000;

    private final PortMappingRepository portMappingRepository;
    private final RelayRepository relayRepository;
    private final VmRepository vmRepository;
    private final kr.ac.pusan.pickle.user.UserRepository userRepository;
    private final RelayGenerations relayGenerations;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final VmEventRepository vmEventRepository;
    private final PortForwardingService portForwardingService;

    public AdminPortMappingService(PortMappingRepository portMappingRepository,
            RelayRepository relayRepository, VmRepository vmRepository,
            kr.ac.pusan.pickle.user.UserRepository userRepository,
            RelayGenerations relayGenerations, NotificationService notificationService,
            AuditService auditService, VmEventRepository vmEventRepository,
            PortForwardingService portForwardingService) {
        this.portMappingRepository = portMappingRepository;
        this.relayRepository = relayRepository;
        this.vmRepository = vmRepository;
        this.userRepository = userRepository;
        this.relayGenerations = relayGenerations;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.vmEventRepository = vmEventRepository;
        this.portForwardingService = portForwardingService;
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<AdminPortMappingResponse> list(UUID publicRelayId, UUID publicVmId,
            PortMappingStatus status, int page, int size) {
        Specification<PortMapping> spec = (root, query, cb) -> cb.conjunction();
        // An id no row has filters to nothing, as a non-matching number did.
        if (publicRelayId != null) {
            Long relayId = relayRepository.findByPublicId(publicRelayId).map(Relay::getId).orElse(-1L);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("relayId"), relayId));
        }
        if (publicVmId != null) {
            Long vmId = vmRepository.findByPublicId(publicVmId).map(Vm::getId).orElse(-1L);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("vmId"), vmId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        Page<PortMapping> result = portMappingRepository.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));

        Map<Long, Relay> relays = relayRepository
                .findAllById(result.getContent().stream().map(PortMapping::getRelayId).toList())
                .stream().collect(Collectors.toMap(Relay::getId, Function.identity()));
        Map<Long, Vm> vms = vmRepository
                .findAllById(result.getContent().stream().map(PortMapping::getVmId).toList())
                .stream().collect(Collectors.toMap(Vm::getId, Function.identity()));
        Map<Long, Set<Long>> failedByRelay = new HashMap<>();
        relays.values().forEach(relay ->
                failedByRelay.put(relay.getId(), portForwardingService.failedIds(relay)));

        Map<Long, UUID> userIds = userPublicIds(result.getContent());
        List<AdminPortMappingResponse> views = result.getContent().stream().map(mapping -> {
            Relay relay = relays.get(mapping.getRelayId());
            Vm vm = vms.get(mapping.getVmId());
            return new AdminPortMappingResponse(mapping.getPublicId(),
                    relay != null ? relay.getPublicId() : null,
                    relay != null ? relay.getName() : "",
                    vm != null ? vm.getPublicId() : null,
                    vm != null ? vm.getName() : null, mapping.getProto(),
                    mapping.getPublicPort(), mapping.getTargetPort(), mapping.getStatus(),
                    mapping.getSuspendedReason(), userIds.get(mapping.getSuspendedBy()),
                    relay == null ? PortForwardApplyState.PENDING
                            : PortForwardingService.applyState(mapping,
                                    relay.getAppliedGeneration(),
                                    failedByRelay.getOrDefault(relay.getId(), Set.of())),
                    mapping.getCtMax(), mapping.getNewConnRate(), mapping.getNewConnBurst(),
                    mapping.getPerSourceRate(), mapping.getPerSourceBurst(),
                    userIds.get(mapping.getCreatedBy()), mapping.getCreatedAt());
        }).toList();
        return PageResponse.of(views, result);
    }

    // ── suspend / unsuspend ──────────────────────────────────────────────────

    @Transactional
    public AdminPortMappingResponse suspend(AuthenticatedUser actor, UUID mappingId,
            String reason, String ip) {
        PortMapping mapping = requireMapping(mappingId);
        if (mapping.getStatus() != PortMappingStatus.ACTIVE) {
            throw mappingStateConflict("이미 정지된 매핑입니다.");
        }
        long generation = relayGenerations.bump(mapping.getRelayId());
        mapping.setStatus(PortMappingStatus.SUSPENDED);
        mapping.setSuspendedReason(reason);
        mapping.setSuspendedBy(actor.id());
        mapping.setLastChangeGeneration(generation);
        Vm vm = vmRepository.findById(mapping.getVmId()).orElse(null);
        if (vm != null) {
            notificationService.publish(
                    notificationService.vmResponsibleIds(vm),
                    NotificationEvent.PORT_MAPPING_SUSPENDED,
                    Map.of("vmId", vm.getPublicId(), "vmName", vm.getName(),
                            "proto", mapping.getProto().name(),
                            "publicPort", mapping.getPublicPort(), "reason", reason),
                    null);
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.PORT_MAPPING_SUSPEND, "port_mapping", mapping.getPublicId(),
                Map.of("auto", false, "relayId", mapping.getRelayId(),
                        "vmId", mapping.getVmId(), "reason", reason), ip);
        return toResponse(mapping);
    }

    @Transactional
    public AdminPortMappingResponse unsuspend(AuthenticatedUser actor, UUID mappingId,
            String ip) {
        PortMapping mapping = requireMapping(mappingId);
        if (mapping.getStatus() != PortMappingStatus.SUSPENDED) {
            throw mappingStateConflict("정지 상태의 매핑이 아닙니다.");
        }
        long generation = relayGenerations.bump(mapping.getRelayId());
        mapping.setStatus(PortMappingStatus.ACTIVE);
        mapping.setSuspendedReason(null);
        mapping.setSuspendedBy(null);
        mapping.setLastChangeGeneration(generation);
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.PORT_MAPPING_UNSUSPEND, "port_mapping", mapping.getPublicId(),
                Map.of("relayId", mapping.getRelayId(), "vmId", mapping.getVmId()), ip);
        return toResponse(mapping);
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Transactional
    public MessageResponse delete(AuthenticatedUser actor, UUID mappingId, String ip) {
        PortMapping mapping = requireMapping(mappingId);
        relayGenerations.bump(mapping.getRelayId());
        portMappingRepository.delete(mapping);
        vmEventRepository.save(new VmEvent(mapping.getVmId(), VmEventType.PORT_FORWARD_DELETE,
                actor.id(), "관리자 삭제 — " + mapping.getProto() + " " + mapping.getPublicPort()));
        // Same channel as an admin suspend: the owning workspace loses an external
        // access path and must not learn it from a dead connection.
        Vm vm = vmRepository.findById(mapping.getVmId()).orElse(null);
        if (vm != null) {
            notificationService.publish(
                    notificationService.vmResponsibleIds(vm),
                    NotificationEvent.PORT_MAPPING_DELETED,
                    Map.of("vmId", vm.getPublicId(), "vmName", vm.getName(),
                            "proto", mapping.getProto().name(),
                            "publicPort", mapping.getPublicPort()),
                    null);
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.PORT_MAPPING_DELETE, "port_mapping", mapping.getPublicId(),
                Map.of("relayId", mapping.getRelayId(), "vmId", mapping.getVmId(),
                        "proto", mapping.getProto().name(),
                        "publicPort", mapping.getPublicPort()), ip);
        return new MessageResponse("매핑을 삭제했습니다. 잠시 후 릴레이에서 제거됩니다.");
    }

    // ── guards ───────────────────────────────────────────────────────────────

    /**
     * Per-field tri-state PATCH (raw body): omitted = keep, explicit null =
     * clear to the agent default, {@code 0} = disable the guard, {@code >0} =
     * explicit limit up to {@link #GUARD_VALUE_MAX}. Every accepted write bumps
     * the relay generation.
     */
    @Transactional
    public AdminPortMappingResponse updateGuards(AuthenticatedUser actor, UUID mappingId,
            JsonNode body, String ip) {
        PortMapping mapping = requireMapping(mappingId);
        Map<String, Object> changes = new LinkedHashMap<>();
        List<FieldValidationError> errors = new java.util.ArrayList<>();
        for (String field : GUARD_FIELDS) {
            if (body == null || !body.has(field)) {
                continue;
            }
            JsonNode value = body.get(field);
            Integer parsed;
            if (value.isNull()) {
                parsed = null;
            } else if (value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0
                    && value.asInt() <= GUARD_VALUE_MAX) {
                parsed = value.asInt();
            } else {
                errors.add(new FieldValidationError(field, field + "는 0 이상 " + GUARD_VALUE_MAX
                        + " 이하의 정수 또는 null이어야 합니다."));
                continue;
            }
            changes.put(field, parsed == null ? "null" : parsed);
            switch (field) {
                case "ctMax" -> mapping.setCtMax(parsed);
                case "newConnRate" -> mapping.setNewConnRate(parsed);
                case "newConnBurst" -> mapping.setNewConnBurst(parsed);
                case "perSourceRate" -> mapping.setPerSourceRate(parsed);
                case "perSourceBurst" -> mapping.setPerSourceBurst(parsed);
                default -> throw new IllegalStateException("unknown guard field " + field);
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
        if (changes.isEmpty()) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("body",
                    "변경할 가드 항목을 최소 1개 지정해야 합니다.")));
        }
        // Cross-field constraint on the RESULTING state: the agent refuses a
        // burst without a positive matching rate (and a strict applier refuses
        // the WHOLE snapshot), so an inconsistent pair must never be stored —
        // it would freeze the relay's entire table at the last generation.
        List<FieldValidationError> pairErrors = new java.util.ArrayList<>();
        if (mapping.getNewConnBurst() != null
                && (mapping.getNewConnRate() == null || mapping.getNewConnRate() <= 0)) {
            pairErrors.add(new FieldValidationError("newConnBurst",
                    "newConnBurst는 newConnRate가 0보다 큰 값일 때만 지정할 수 있습니다."));
        }
        if (mapping.getPerSourceBurst() != null
                && (mapping.getPerSourceRate() == null || mapping.getPerSourceRate() <= 0)) {
            pairErrors.add(new FieldValidationError("perSourceBurst",
                    "perSourceBurst는 perSourceRate가 0보다 큰 값일 때만 지정할 수 있습니다."));
        }
        if (!pairErrors.isEmpty()) {
            throw ApiException.validationFailed(pairErrors); // tx rollback discards the edits
        }
        long generation = relayGenerations.bump(mapping.getRelayId());
        mapping.setLastChangeGeneration(generation);
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.PORT_MAPPING_GUARDS_UPDATE, "port_mapping", mapping.getPublicId(),
                Map.of("relayId", mapping.getRelayId(), "vmId", mapping.getVmId(),
                        "changes", changes), ip);
        return toResponse(mapping);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Single-mapping response with relay/VM context and derived apply state. */
    private AdminPortMappingResponse toResponse(PortMapping mapping) {
        Relay relay = relayRepository.findById(mapping.getRelayId()).orElseThrow();
        Vm vm = vmRepository.findById(mapping.getVmId()).orElse(null);
        Map<Long, UUID> userIds = userPublicIds(List.of(mapping));
        return new AdminPortMappingResponse(mapping.getPublicId(), relay.getPublicId(), relay.getName(),
                vm != null ? vm.getPublicId() : null, vm != null ? vm.getName() : null,
                mapping.getProto(),
                mapping.getPublicPort(), mapping.getTargetPort(), mapping.getStatus(),
                mapping.getSuspendedReason(), userIds.get(mapping.getSuspendedBy()),
                PortForwardingService.applyState(mapping, relay.getAppliedGeneration(),
                        portForwardingService.failedIds(relay)),
                mapping.getCtMax(), mapping.getNewConnRate(), mapping.getNewConnBurst(),
                mapping.getPerSourceRate(), mapping.getPerSourceBurst(),
                userIds.get(mapping.getCreatedBy()), mapping.getCreatedAt());
    }

    /** Batch account join for {@code createdBy}/{@code suspendedBy}. */
    private Map<Long, UUID> userPublicIds(List<PortMapping> mappings) {
        List<Long> ids = java.util.stream.Stream.concat(
                        mappings.stream().map(PortMapping::getCreatedBy),
                        mappings.stream().map(PortMapping::getSuspendedBy))
                .filter(java.util.Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of()
                : userRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(kr.ac.pusan.pickle.user.User::getId,
                                kr.ac.pusan.pickle.user.User::getPublicId));
    }

    private PortMapping requireMapping(UUID mappingId) {
        return portMappingRepository.findByPublicId(mappingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCodes.RESOURCE_NOT_FOUND, "리소스를 찾을 수 없습니다",
                        "해당 포트 매핑이 존재하지 않습니다."));
    }

    private static ApiException mappingStateConflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                "현재 상태에서는 수행할 수 없는 작업입니다", detail);
    }
}
