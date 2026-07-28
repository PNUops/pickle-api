package kr.ac.pusan.pickle.relay;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import kr.ac.pusan.pickle.relay.dto.AdminPortMappingView;
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

    private final PortMappingRepository portMappingRepository;
    private final RelayRepository relayRepository;
    private final VmRepository vmRepository;
    private final RelayGenerations relayGenerations;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final VmEventRepository vmEventRepository;
    private final PortForwardingService portForwardingService;

    public AdminPortMappingService(PortMappingRepository portMappingRepository,
            RelayRepository relayRepository, VmRepository vmRepository,
            RelayGenerations relayGenerations, NotificationService notificationService,
            AuditService auditService, VmEventRepository vmEventRepository,
            PortForwardingService portForwardingService) {
        this.portMappingRepository = portMappingRepository;
        this.relayRepository = relayRepository;
        this.vmRepository = vmRepository;
        this.relayGenerations = relayGenerations;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.vmEventRepository = vmEventRepository;
        this.portForwardingService = portForwardingService;
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<AdminPortMappingView> list(Long relayId, Long vmId,
            PortMappingStatus status, int page, int size) {
        Specification<PortMapping> spec = (root, query, cb) -> cb.conjunction();
        if (relayId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("relayId"), relayId));
        }
        if (vmId != null) {
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

        List<AdminPortMappingView> views = result.getContent().stream().map(mapping -> {
            Relay relay = relays.get(mapping.getRelayId());
            Vm vm = vms.get(mapping.getVmId());
            return new AdminPortMappingView(mapping.getId(), mapping.getRelayId(),
                    relay != null ? relay.getName() : "", mapping.getVmId(),
                    vm != null ? vm.getName() : null, mapping.getProto(),
                    mapping.getPublicPort(), mapping.getTargetPort(), mapping.getStatus(),
                    mapping.getSuspendedReason(), mapping.getSuspendedBy(),
                    relay == null ? PortForwardApplyState.PENDING
                            : PortForwardingService.applyState(mapping,
                                    relay.getAppliedGeneration(),
                                    failedByRelay.getOrDefault(relay.getId(), Set.of())),
                    mapping.getCtMax(), mapping.getNewConnRate(), mapping.getNewConnBurst(),
                    mapping.getPerSourceRate(), mapping.getPerSourceBurst(),
                    mapping.getCreatedBy(), mapping.getCreatedAt());
        }).toList();
        return PageResponse.of(views, result);
    }

    // ── suspend / unsuspend ──────────────────────────────────────────────────

    @Transactional
    public MessageResponse suspend(AuthenticatedUser actor, long mappingId, String reason,
            String ip) {
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
                    notificationService.groupRoleHolderIds(vm.getGroupId(), true),
                    NotificationEvent.PORT_MAPPING_SUSPENDED,
                    Map.of("vmId", vm.getId(), "vmName", vm.getName(),
                            "proto", mapping.getProto().name(),
                            "publicPort", mapping.getPublicPort(), "reason", reason),
                    null);
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.PORT_MAPPING_SUSPEND, "port_mapping", mappingId,
                Map.of("auto", false, "relayId", mapping.getRelayId(),
                        "vmId", mapping.getVmId(), "reason", reason), ip);
        return new MessageResponse("매핑을 정지했습니다. 잠시 후 릴레이에서 제거됩니다.");
    }

    @Transactional
    public MessageResponse unsuspend(AuthenticatedUser actor, long mappingId, String ip) {
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
                AuditService.PORT_MAPPING_UNSUSPEND, "port_mapping", mappingId,
                Map.of("relayId", mapping.getRelayId(), "vmId", mapping.getVmId()), ip);
        return new MessageResponse("매핑 정지를 해제했습니다. 잠시 후 릴레이에 다시 반영됩니다.");
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Transactional
    public MessageResponse delete(AuthenticatedUser actor, long mappingId, String ip) {
        PortMapping mapping = requireMapping(mappingId);
        relayGenerations.bump(mapping.getRelayId());
        portMappingRepository.delete(mapping);
        vmEventRepository.save(new VmEvent(mapping.getVmId(), VmEventType.PORT_FORWARD_DELETE,
                actor.id(), "관리자 삭제 — " + mapping.getProto() + " " + mapping.getPublicPort()));
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.PORT_MAPPING_DELETE, "port_mapping", mappingId,
                Map.of("relayId", mapping.getRelayId(), "vmId", mapping.getVmId(),
                        "proto", mapping.getProto().name(),
                        "publicPort", mapping.getPublicPort()), ip);
        return new MessageResponse("매핑을 삭제했습니다. 잠시 후 릴레이에서 제거됩니다.");
    }

    // ── guards ───────────────────────────────────────────────────────────────

    /**
     * Per-field tri-state PATCH (raw body): omitted = keep, explicit null =
     * clear to the agent default, {@code 0} = disable the guard, {@code >0} =
     * explicit limit. Every accepted write bumps the relay generation.
     */
    @Transactional
    public AdminPortMappingView updateGuards(AuthenticatedUser actor, long mappingId,
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
            } else if (value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0) {
                parsed = value.asInt();
            } else {
                errors.add(new FieldValidationError(field, "0 이상의 정수 또는 null이어야 합니다."));
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
        long generation = relayGenerations.bump(mapping.getRelayId());
        mapping.setLastChangeGeneration(generation);
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.PORT_MAPPING_GUARDS_UPDATE, "port_mapping", mappingId,
                Map.of("relayId", mapping.getRelayId(), "vmId", mapping.getVmId(),
                        "changes", changes), ip);
        Relay relay = relayRepository.findById(mapping.getRelayId()).orElseThrow();
        Vm vm = vmRepository.findById(mapping.getVmId()).orElse(null);
        return new AdminPortMappingView(mapping.getId(), mapping.getRelayId(), relay.getName(),
                mapping.getVmId(), vm != null ? vm.getName() : null, mapping.getProto(),
                mapping.getPublicPort(), mapping.getTargetPort(), mapping.getStatus(),
                mapping.getSuspendedReason(), mapping.getSuspendedBy(),
                PortForwardingService.applyState(mapping, relay.getAppliedGeneration(),
                        portForwardingService.failedIds(relay)),
                mapping.getCtMax(), mapping.getNewConnRate(), mapping.getNewConnBurst(),
                mapping.getPerSourceRate(), mapping.getPerSourceBurst(), mapping.getCreatedBy(),
                mapping.getCreatedAt());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private PortMapping requireMapping(long mappingId) {
        return portMappingRepository.findById(mappingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCodes.RESOURCE_NOT_FOUND, "리소스를 찾을 수 없습니다",
                        "해당 포트 매핑이 존재하지 않습니다."));
    }

    private static ApiException mappingStateConflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                "현재 상태에서는 수행할 수 없는 작업입니다", detail);
    }
}
