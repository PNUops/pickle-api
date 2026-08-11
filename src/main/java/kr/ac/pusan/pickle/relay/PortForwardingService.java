package kr.ac.pusan.pickle.relay;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.VmAccessService;
import kr.ac.pusan.pickle.audit.AuditIds;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.ipam.IpAddressResolver;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.relay.dto.CreatePortForwardingRequest;
import kr.ac.pusan.pickle.relay.dto.PortForwardingView;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmEvent;
import kr.ac.pusan.pickle.vm.VmEventRepository;
import kr.ac.pusan.pickle.vm.VmEventType;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Self-service VM port forwarding (contract tag {@code port-forwarding}):
 * expose an arbitrary VM TCP/UDP port through a relay's public port band.
 * Same authorization shape as publishing: mutating ops need the owning
 * workspace's OWNER/EDITOR (non-member → 404 mask), reads need membership.
 *
 * <p>Allocation policy: the public port is <b>random within the relay's
 * band</b> (sequential numbers leak creation order and invite enumeration),
 * and a port number used by ANY proto counts as taken — the unique key keeps
 * proto for a future dual-proto option, but today's behavior is cross-proto
 * exclusive. Race-freedom comes from bumping the relay generation FIRST: the
 * bump row-locks the relay until commit, serializing every mapping write of
 * that relay (see {@link RelayGenerations}).</p>
 */
@Service
public class PortForwardingService {

    static final String ALLOC_RATE_LIMIT_SCOPE = "pf_alloc";
    static final int RANDOM_ALLOC_ATTEMPTS = 20;
    /**
     * Guest SSH port, never a legal forwarding target: a mapping to it would
     * expose the guest's own sshd to the internet, bypassing the SSH gateway
     * and with it every per-VM SSH policy the gateway enforces (password
     * authentication above all).
     */
    static final int GUEST_SSH_PORT = 22;

    private final VmRepository vmRepository;
    private final VmAccessService vmAccessService;
    private final RelayRepository relayRepository;
    private final PortMappingRepository portMappingRepository;
    private final RelayGenerations relayGenerations;
    private final SettingsService settingsService;
    private final RateLimitService rateLimitService;
    private final IpAddressResolver ipAddressResolver;
    private final VmEventRepository vmEventRepository;
    private final AuditService auditService;
    private final AuditIds auditIds;
    private final NotificationService notificationService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public PortForwardingService(VmRepository vmRepository,
            VmAccessService vmAccessService, RelayRepository relayRepository,
            PortMappingRepository portMappingRepository, RelayGenerations relayGenerations,
            SettingsService settingsService, RateLimitService rateLimitService,
            IpAddressResolver ipAddressResolver, VmEventRepository vmEventRepository,
            AuditService auditService, AuditIds auditIds, NotificationService notificationService,
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.vmRepository = vmRepository;
        this.vmAccessService = vmAccessService;
        this.relayRepository = relayRepository;
        this.portMappingRepository = portMappingRepository;
        this.relayGenerations = relayGenerations;
        this.settingsService = settingsService;
        this.rateLimitService = rateLimitService;
        this.ipAddressResolver = ipAddressResolver;
        this.vmEventRepository = vmEventRepository;
        this.auditService = auditService;
        this.auditIds = auditIds;
        this.notificationService = notificationService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PortForwardingView> list(AuthenticatedUser actor, UUID publicVmId) {
        Vm vm = requireVmMember(actor, publicVmId);
        List<PortMapping> mappings = portMappingRepository.findByVmIdOrderByIdAsc(vm.getId());
        return mappings.stream().map(this::toView).toList();
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Transactional
    public PortForwardingView create(AuthenticatedUser actor, UUID publicVmId,
            CreatePortForwardingRequest request, String ip) {
        Vm vm = requireVmOwnerOrEditor(actor, publicVmId);
        long vmId = vm.getId();
        if (!settingsService.bool(SettingsService.PORT_FORWARDING_ENABLED, false)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.PORT_FORWARDING_DISABLED,
                    "포트 포워딩이 비활성화되어 있습니다",
                    "현재 포트 포워딩 기능이 꺼져 있어 새로 만들 수 없습니다. 관리자에게 문의해 주세요.");
        }
        if (request.targetPort() == GUEST_SSH_PORT) {
            throw guestSshPortRefused();
        }
        if (vm.getStatus() != VmStatus.RUNNING) {
            throw invalidVmState("RUNNING 상태의 VM만 포트를 공개할 수 있습니다. (현재 상태 "
                    + vm.getStatus() + ")");
        }
        String targetAddr = ipAddressResolver.liveHostIp(vm.getIpAllocationId(), vmId);
        if (targetAddr == null) {
            throw invalidVmState("VM에 사용 중인 IP가 없어 포트를 공개할 수 없습니다.");
        }
        rateLimitService.hitHourly(ALLOC_RATE_LIMIT_SCOPE, "user:" + actor.id(),
                settingsService.integer(SettingsService.PORT_FORWARD_ALLOC_LIMIT_PER_HOUR, 20));
        Relay relay = relayRepository.findFirstByEnabledTrueOrderByIdAsc()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        ErrorCodes.PORT_FORWARDING_DISABLED, "포트 포워딩이 비활성화되어 있습니다",
                        "사용 가능한 릴레이가 없습니다. 관리자에게 문의해 주세요."));

        // Bump FIRST: row-locks the relay until commit, so concurrent creates
        // (and deletes) of this relay serialize — the not-exists check inside
        // the allocation insert can then never race cross-proto.
        long generation = relayGenerations.bump(relay.getId());
        long mappingId = allocate(relay, vmId, request.proto(), request.targetPort(),
                generation, actor.id());
        alertOnBandUsage(relay);

        vmEventRepository.save(new VmEvent(vmId, VmEventType.PORT_FORWARD_CREATE, actor.id(),
                request.proto() + " 공개 포트 할당 → 대상 포트 " + request.targetPort()));
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.VM_PORT_FORWARD_CREATE, "vm", vm.getPublicId(),
                Map.of("mappingId", auditIds.portMapping(mappingId), "relayId", relay.getPublicId(),
                        "proto", request.proto().name(), "targetPort", request.targetPort()), ip);
        return toView(portMappingRepository.findById(mappingId).orElseThrow());
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Transactional
    public MessageResponse delete(AuthenticatedUser actor, UUID publicVmId,
            UUID portForwardingId, String ip) {
        Vm vm = requireVmOwnerOrEditor(actor, publicVmId);
        long vmId = vm.getId();
        PortMapping mapping = portMappingRepository.findByPublicId(portForwardingId)
                .filter(row -> row.getVmId() == vmId)
                .orElseThrow(PortForwardingService::mappingNotFound);
        relayGenerations.bump(mapping.getRelayId());
        portMappingRepository.delete(mapping);
        vmEventRepository.save(new VmEvent(vmId, VmEventType.PORT_FORWARD_DELETE, actor.id(),
                mapping.getProto() + " " + mapping.getPublicPort() + " 공개 해제"));
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.VM_PORT_FORWARD_DELETE, "vm", vm.getPublicId(),
                Map.of("mappingId", mapping.getPublicId(), "relayId", auditIds.relay(mapping.getRelayId()),
                        "proto", mapping.getProto().name(),
                        "publicPort", mapping.getPublicPort()), ip);
        return new MessageResponse("포트 포워딩 삭제를 접수했습니다. 잠시 후 릴레이에서 제거됩니다.");
    }

    // ── allocation ───────────────────────────────────────────────────────────

    /**
     * Random pick with bounded retries, then one uniform pick over the exact
     * free set. Both paths insert through a {@code not exists} guard covering
     * BOTH protos of the (relay, port) pair — cross-proto exclusive even
     * though the unique constraint itself is per-proto.
     */
    private long allocate(Relay relay, long vmId, PortMappingProto proto, int targetPort,
            long generation, long actorId) {
        String insertSql = """
                insert into port_mappings (relay_id, vm_id, proto, public_port, target_port,
                                           status, last_change_generation, created_by)
                select ?, ?, ?, ?, ?, 'ACTIVE', ?, ?
                 where not exists (select 1 from port_mappings
                                    where relay_id = ? and public_port = ?)
                returning id
                """;
        for (int attempt = 0; attempt < RANDOM_ALLOC_ATTEMPTS; attempt++) {
            int candidate = relay.getPortBandStart() + random.nextInt(relay.bandSize());
            Long id = jdbcTemplate.query(insertSql,
                    (ResultSetExtractor<Long>) rs -> rs.next() ? rs.getLong(1) : null,
                    relay.getId(), vmId, proto.name(), candidate, targetPort, generation,
                    actorId, relay.getId(), candidate);
            if (id != null) {
                return id;
            }
        }
        // Band nearly full: pick uniformly over the actual free set instead of
        // hammering random numbers, then give up honestly.
        Integer candidate = jdbcTemplate.query("""
                select p from generate_series(?, ?) as p
                 where not exists (select 1 from port_mappings m
                                    where m.relay_id = ? and m.public_port = p)
                 order by random() limit 1
                """, (ResultSetExtractor<Integer>) rs -> rs.next() ? rs.getInt(1) : null,
                relay.getPortBandStart(), relay.getPortBandEnd(), relay.getId());
        if (candidate != null) {
            Long id = jdbcTemplate.query(insertSql,
                    (ResultSetExtractor<Long>) rs -> rs.next() ? rs.getLong(1) : null,
                    relay.getId(), vmId, proto.name(), candidate, targetPort, generation,
                    actorId, relay.getId(), candidate);
            if (id != null) {
                return id;
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.PUBLIC_PORT_EXHAUSTED,
                "할당 가능한 공개 포트가 없습니다",
                "릴레이의 공개 포트 대역이 모두 사용 중입니다. 관리자에게 문의해 주세요.");
    }

    /** Band-usage threshold alert, deduped per 5%-bucket per relay. */
    private void alertOnBandUsage(Relay relay) {
        int threshold = settingsService.integer(
                SettingsService.PORT_FORWARD_BAND_ALERT_PERCENT, 80);
        Long used = jdbcTemplate.queryForObject(
                "select count(distinct public_port) from port_mappings where relay_id = ?",
                Long.class, relay.getId());
        int percent = (int) (used * 100 / relay.bandSize());
        if (percent < threshold) {
            return;
        }
        int bucket = percent / 5 * 5;
        notificationService.publish(notificationService.sysAdminIds(),
                NotificationEvent.RELAY_BAND_USAGE_HIGH,
                Map.of("relayId", relay.getId(), "relayName", relay.getName(),
                        "usagePercent", percent, "thresholdPercent", threshold),
                "relay_band_usage:" + relay.getId() + ":" + bucket);
    }

    // ── view assembly ────────────────────────────────────────────────────────

    private PortForwardingView toView(PortMapping mapping) {
        Relay relay = relayRepository.findById(mapping.getRelayId()).orElseThrow();
        return new PortForwardingView(mapping.getPublicId(), mapping.getProto(),
                mapping.getPublicPort(), relay.getPublicHost(), mapping.getTargetPort(),
                mapping.getStatus(),
                applyState(mapping, relay.getAppliedGeneration(), failedIds(relay)),
                mapping.getCreatedAt());
    }

    /**
     * Derived apply state (never stored): FAILED overrides when the relay's
     * stored lastError names this mapping; else ACTIVE iff the relay confirmed
     * a generation at or past the mapping's last change.
     */
    static PortForwardApplyState applyState(PortMapping mapping, long appliedGeneration,
            Set<Long> failedMappingIds) {
        if (failedMappingIds.contains(mapping.getId())) {
            return PortForwardApplyState.FAILED;
        }
        return appliedGeneration >= mapping.getLastChangeGeneration()
                ? PortForwardApplyState.ACTIVE : PortForwardApplyState.PENDING;
    }

    /** Mapping ids named in the relay's sanitized lastError JSON. */
    Set<Long> failedIds(Relay relay) {
        Set<Long> ids = new HashSet<>();
        if (relay.getLastError() == null || relay.getLastError().isBlank()) {
            return ids;
        }
        try {
            JsonNode node = objectMapper.readTree(relay.getLastError());
            if (node.isArray()) {
                node.forEach(item -> {
                    JsonNode mappingId = item.get("mappingId");
                    if (mappingId != null && mappingId.isIntegralNumber()) {
                        ids.add(mappingId.asLong());
                    }
                });
            }
        } catch (RuntimeException ignored) {
            // stored by us, but defensive: unparseable error text fails no view
        }
        return ids;
    }

    // ── authorization (publishing pattern: 404 mask, 403 for members) ───────

    private Vm requireVmMember(AuthenticatedUser actor, UUID vmId) {
        return vmAccessService.of(actor, vmId).requireVisible();
    }

    private Vm requireVmOwnerOrEditor(AuthenticatedUser actor, UUID vmId) {
        return vmAccessService.of(actor, vmId).requireAtLeast(ResourceRole.EDITOR,
                "포트 포워딩을 관리할 권한이 없습니다",
                "이 VM의 소유자 또는 편집자만 포트포워딩을 설정할 수 있습니다.");
    }

    /**
     * 422 in the same shape bean validation produces (field {@code targetPort}),
     * because to the caller this IS a rejected request value. The network layer
     * refuses the same traffic independently; this check exists so the refusal
     * is visible at request time instead of as a silently dead mapping.
     */
    private static ApiException guestSshPortRefused() {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.VALIDATION_FAILED,
                "입력값이 올바르지 않습니다",
                "SSH 포트(22)는 공개 대상으로 지정할 수 없습니다. VM 접속은 SSH 게이트웨이를 이용해 주세요.",
                List.of(new FieldValidationError("targetPort",
                        "22번 포트는 공개할 수 없습니다")), null);
    }

    private static ApiException invalidVmState(String detail) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                "현재 상태에서는 포트를 공개할 수 없습니다", detail);
    }

    private static ApiException mappingNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 포트 포워딩이 존재하지 않습니다.");
    }
}
