package kr.ac.pusan.pickle.relay;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.pusan.pickle.audit.AuditIds;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.networkpolicy.NetworkPolicyCapability;
import kr.ac.pusan.pickle.networkpolicy.SourcePolicyProducer;
import kr.ac.pusan.pickle.networkpolicy.SourcePolicyActivationService;
import kr.ac.pusan.pickle.networkpolicy.SourcePolicyWire;
import kr.ac.pusan.pickle.networkpolicy.VmNetworkPathOperationStore;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.relay.dto.RelaySyncRequest;
import kr.ac.pusan.pickle.relay.dto.RelaySyncResponse;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The relay sync heartbeat: validates the agent's report, updates the relay's
 * observability state, accumulates traffic counters (auto-suspending abusive
 * mappings), and answers the desired mapping set.
 *
 * <p><b>Everything runs in ONE transaction</b>, and the generation + snapshot
 * are read by a single SQL statement (one MVCC view — the review-mandated
 * atomicity). Reading them separately under READ COMMITTED could pair a new
 * generation with an older row set, which the agent would then confirm as
 * applied: permanently stale rules behind a console showing 반영 완료.</p>
 *
 * <p>All report fields are claims by the relay, not measurements: strings are
 * control-stripped and truncated before persisting, {@code appliedGeneration}
 * must stay within {@code 0 ≤ x ≤ mapping_generation} and never regress —
 * the reported value is then discarded and the relay gets the full snapshot so
 * a confused (or lying) agent converges instead of wedging. A report of 0 is
 * the routine "booted with no usable snapshot" case and only logged; any other
 * out-of-range value is audited as a violation.</p>
 */
@Service
public class RelaySyncService {

    static final String RETIREMENT_CAPABILITY = "mapping-retirement-v1";
    static final long UINT32_MAX = 4_294_967_295L;

    /** Server-side cap on any agent-reported string persisted or audited. */
    static final int REPORTED_TEXT_MAX = 1024;

    /**
     * Counter rows processed per report; anything past this is ignored, never
     * rejected. Counters are observability, the same request carries the
     * heartbeat and confirms the applied generation — refusing the body over a
     * row count would stop suspend/delete from ever reaching the relay while
     * its existing rules keep serving. Truncating loses a few readings for one
     * cycle instead. Set far above the live mapping count a single relay can
     * hold (its public-port band), so real reports are never cut.
     */
    static final int MAX_REPORTED_COUNTERS = 4096;

    private static final Logger log = LoggerFactory.getLogger(RelaySyncService.class);

    /**
     * Generation + snapshot in one statement (single MVCC view). SUSPENDED
     * mappings are excluded (the agent only sees desired state), and the
     * target address is resolved live from the VM's own ALLOCATED
     * ip_allocations row — never stored on the mapping.
     */
    private static final String SNAPSHOT_SQL = """
            select r.mapping_generation, r.retirement_armed,
                   r.acknowledged_retirement_high_water, m.id as row_id,
                   coalesce(m.consumer_mapping_id, m.id) as id, lower(m.proto) as proto,
                   m.public_port, m.target_port,
                   m.ct_max, m.new_conn_rate, m.new_conn_burst,
                   m.per_source_rate, m.per_source_burst, m.source_policy_generation, m.flow_mark,
                   host(a.ip) as target_addr
              from relays r
              left join port_mappings m on m.relay_id = r.id
                   and ((not r.retirement_armed and m.delivery_state = 'LEGACY'
                         and m.status = 'ACTIVE')
                        or (r.retirement_armed and m.delivery_state = 'ACTIVE'
                            and m.status in ('PENDING', 'ACTIVE')))
              left join vms v on v.id = m.vm_id
              left join ip_allocations a on a.id = v.ip_allocation_id and a.vm_id = v.id
                                        and a.status = 'ALLOCATED'
             where r.id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RelayGenerations relayGenerations;
    private final SettingsService settingsService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final AuditIds auditIds;
    private final ObjectMapper objectMapper;
    private final RelayCapabilityObservationService capabilityObservations;
    private final SourcePolicyProducer sourcePolicies;
    private final NetworkPolicyCapability networkPolicyCapability;
    private final SourcePolicyActivationService sourcePolicyActivation;
    private final VmNetworkPathOperationStore networkPaths;

    public RelaySyncService(JdbcTemplate jdbcTemplate, RelayGenerations relayGenerations,
            SettingsService settingsService, NotificationService notificationService,
            AuditService auditService, AuditIds auditIds, ObjectMapper objectMapper,
            RelayCapabilityObservationService capabilityObservations,
            SourcePolicyProducer sourcePolicies,
            NetworkPolicyCapability networkPolicyCapability,
            SourcePolicyActivationService sourcePolicyActivation,
            VmNetworkPathOperationStore networkPaths) {
        this.jdbcTemplate = jdbcTemplate;
        this.relayGenerations = relayGenerations;
        this.settingsService = settingsService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.auditIds = auditIds;
        this.objectMapper = objectMapper;
        this.capabilityObservations = capabilityObservations;
        this.sourcePolicies = sourcePolicies;
        this.networkPolicyCapability = networkPolicyCapability;
        this.sourcePolicyActivation = sourcePolicyActivation;
        this.networkPaths = networkPaths;
    }

    @Transactional
    public RelaySyncResponse sync(long relayId, RelaySyncRequest request) {
        Set<String> currentCapabilities = request.capabilities() == null ? Set.of()
                : Set.copyOf(request.capabilities());
        capabilityObservations.observe(relayId,
                request.capabilities() == null ? List.of() : request.capabilities());
        sourcePolicyActivation.activateRelay(relayId);
        processRetirementHandshake(relayId, request, currentCapabilities);
        GenerationState state = jdbcTemplate.queryForObject("""
                select applied_generation, mapping_generation from relays where id = ?
                """, (rs, rowNum) -> new GenerationState(rs.getLong(1), rs.getLong(2)), relayId);

        String agentVersion = Texts.sanitizeReported(request.agentVersion(), REPORTED_TEXT_MAX);
        List<SanitizedError> errors = sanitizeErrors(relayId, request.lastError());
        String lastErrorJson = errors.isEmpty() ? null : objectMapper.writeValueAsString(errors);

        // appliedGeneration must stay in [stored, current] — anything else is
        // impossible for an honest agent (@Min(0) already rejected negatives).
        long reported = request.appliedGeneration();
        boolean discarded = reported > state.current() || reported < state.applied();
        // A relay that boots without a usable snapshot honestly reports 0, and
        // 0 can only ever look like a decrease. That is an ordinary restart,
        // not a monotonicity violation: the reported value is still discarded
        // and the full snapshot still answered, but auditing it as a security
        // event on every agent restart would only teach operators to ignore
        // the action.
        boolean restart = discarded && reported == 0;
        boolean violation = discarded && !restart;
        long validated = discarded ? state.applied() : reported;
        if (restart) {
            log.info("relay {} reported generation 0 (agent restart; stored applied {})",
                    relayId, state.applied());
        }
        if (violation) {
            // Direct record (not after-commit): a security signal, keep it even
            // if something later in this tx were to fail.
            auditService.record(null, AuditService.ACTOR_ROLE_RELAY,
                    AuditService.RELAY_SYNC_VIOLATION, "relay", relayPublicId(relayId),
                    Map.of("reported", reported, "storedApplied", state.applied(),
                            "currentGeneration", state.current(),
                            "agentVersion", agentVersion == null ? "" : agentVersion), null);
            log.warn("relay {} reported impossible appliedGeneration {} (stored {}, current {})",
                    relayId, reported, state.applied(), state.current());
        }

        // Heartbeat: the sync IS the liveness signal, so contact-lost clears here.
        jdbcTemplate.update("""
                update relays
                   set last_contact_at = now(), applied_generation = ?, agent_version = ?,
                       last_error = ?, contact_lost_since = null, updated_at = now()
                 where id = ?
                """, validated, agentVersion, lastErrorJson, relayId);

        if (request.counters() != null) {
            accumulateCounters(relayId, request.counters());
        }

        // A discarded report (violation or restart) always gets the full
        // snapshot so a confused agent converges instead of wedging.
        return readSnapshot(relayId, validated, discarded, currentCapabilities);
    }

    // ── counters (reset-aware) ───────────────────────────────────────────────

    /**
     * Accumulates raw (cumulative since agent start) readings into totals.
     * Any raw value below its stored last reading means the agent restarted:
     * the row re-baselines (delta = raw) — a decrease is NEVER a negative
     * delta. Per-minute rates against {@code last_delta_at} feed the
     * auto-suspend thresholds; a breach suspends the mapping in this same
     * transaction, so the snapshot answered below already excludes it.
     *
     * <p>Only the first {@link #MAX_REPORTED_COUNTERS} rows are processed; the
     * rest are dropped silently (the agent rotates its reporting window, so a
     * mapping cut here is covered by a later report).</p>
     */
    private void accumulateCounters(long relayId,
            List<RelaySyncRequest.ReportedMappingCounters> allReportedRows) {
        List<RelaySyncRequest.ReportedMappingCounters> reportedRows =
                allReportedRows.size() > MAX_REPORTED_COUNTERS
                        ? allReportedRows.subList(0, MAX_REPORTED_COUNTERS) : allReportedRows;
        if (reportedRows.size() < allReportedRows.size()) {
            log.warn("relay {} reported {} counter rows — only the first {} were processed",
                    relayId, allReportedRows.size(), MAX_REPORTED_COUNTERS);
        }
        long connsPerMinLimit = settingsService.integer(
                SettingsService.PORT_FORWARD_SUSPEND_CONNS_PER_MIN, 6000);
        long mbytesPerMinLimit = settingsService.integer(
                SettingsService.PORT_FORWARD_SUSPEND_MBYTES_PER_MIN, 1000);
        // A report row is only credited to a mapping this relay owns — one
        // batch read of the relay's OWN ids; foreign mappingIds never even
        // reach a query parameter, they simply miss this set.
        Map<Long, Long> ownedIds = jdbcTemplate.query("""
                select coalesce(consumer_mapping_id, id), id
                  from port_mappings where relay_id = ?
                """, rs -> {
            Map<Long, Long> result = new java.util.HashMap<>();
            while (rs.next()) {
                result.put(rs.getLong(1), rs.getLong(2));
            }
            return result;
        }, relayId);
        Instant now = Instant.now();
        for (RelaySyncRequest.ReportedMappingCounters reportedRow : reportedRows) {
            if (reportedRow.mappingId() == null || !ownedIds.containsKey(reportedRow.mappingId())) {
                continue;
            }
            long mappingId = ownedIds.get(reportedRow.mappingId());
            Raw raw = Raw.of(reportedRow);
            if (raw.beyondSanity()) {
                // Insane magnitude (> 2^53): discard the whole reading like a
                // reset to nothing — totals never ingest it, the stored
                // baseline stays put, and the event is audited. Bounds the
                // bigint totals against a lying or corrupted agent.
                auditService.record(null, AuditService.ACTOR_ROLE_RELAY,
                        AuditService.RELAY_SYNC_VIOLATION, "relay", relayPublicId(relayId),
                        Map.of("kind", "counter_sanity", "mappingId", auditIds.portMapping(mappingId),
                                "maxReported", String.valueOf(raw.max())), null);
                log.warn("relay {} reported an insane counter for mapping {} (max {})",
                        relayId, mappingId, raw.max());
                continue;
            }
            CounterRow last = jdbcTemplate.query("""
                    select conn_total, bytes_total, drop_total, last_new_conns, last_in_packets,
                           last_in_bytes, last_out_packets, last_out_bytes, last_rate_dropped,
                           last_conn_dropped, last_per_source_dropped, last_delta_at
                      from port_mapping_counters where mapping_id = ?
                    """, rs -> rs.next() ? CounterRow.of(rs) : null, mappingId);

            boolean reset = last != null && raw.anyBelow(last);
            long deltaConns = reset || last == null ? raw.newConns()
                    : raw.newConns() - last.lastNewConns();
            long deltaBytes = reset || last == null ? raw.inBytes() + raw.outBytes()
                    : (raw.inBytes() - last.lastInBytes()) + (raw.outBytes() - last.lastOutBytes());
            long deltaDrops = reset || last == null ? raw.drops()
                    : raw.drops() - last.lastDrops();

            jdbcTemplate.update("""
                    insert into port_mapping_counters (mapping_id, conn_total, bytes_total,
                        drop_total, last_new_conns, last_in_packets, last_in_bytes,
                        last_out_packets, last_out_bytes, last_rate_dropped, last_conn_dropped,
                        last_per_source_dropped, last_delta_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                    on conflict (mapping_id) do update set
                        conn_total = excluded.conn_total, bytes_total = excluded.bytes_total,
                        drop_total = excluded.drop_total, last_new_conns = excluded.last_new_conns,
                        last_in_packets = excluded.last_in_packets,
                        last_in_bytes = excluded.last_in_bytes,
                        last_out_packets = excluded.last_out_packets,
                        last_out_bytes = excluded.last_out_bytes,
                        last_rate_dropped = excluded.last_rate_dropped,
                        last_conn_dropped = excluded.last_conn_dropped,
                        last_per_source_dropped = excluded.last_per_source_dropped,
                        last_delta_at = now(), updated_at = now()
                    """, mappingId,
                    (last == null ? 0 : last.connTotal()) + deltaConns,
                    (last == null ? 0 : last.bytesTotal()) + deltaBytes,
                    (last == null ? 0 : last.dropTotal()) + deltaDrops,
                    raw.newConns(), raw.inPackets(), raw.inBytes(), raw.outPackets(),
                    raw.outBytes(), raw.rateDropped(), raw.connDropped(), raw.perSourceDropped());

            if (last == null || last.lastDeltaAt() == null) {
                continue; // first report: baseline only, no rate yet
            }
            double minutes = Math.max(1, Duration.between(last.lastDeltaAt(), now).toSeconds())
                    / 60.0;
            double connsPerMin = deltaConns / minutes;
            double mbytesPerMin = deltaBytes / (1024.0 * 1024.0) / minutes;
            if (connsPerMin > connsPerMinLimit || mbytesPerMin > mbytesPerMinLimit) {
                autoSuspend(relayId, mappingId, Math.round(connsPerMin), Math.round(mbytesPerMin),
                        connsPerMinLimit, mbytesPerMinLimit);
            }
        }
    }

    /** Threshold breach: suspend in THIS tx so the same response excludes it. */
    private void autoSuspend(long relayId, long mappingId, long connsPerMin, long mbytesPerMin,
            long connsLimit, long mbytesLimit) {
        String currentStatus = jdbcTemplate.query("""
                select status from port_mappings where id = ? and relay_id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, mappingId, relayId);
        if (!"ACTIVE".equals(currentStatus)) {
            // Late counters still contribute to observability, but a mapping
            // already PENDING/SUSPENDED/REMOVING owns a different lifecycle
            // transition. Never supersede or roll back its sync response.
            return;
        }
        String reason = "트래픽 임계값 초과로 자동 정지 (분당 신규 연결 " + connsPerMin
                + "건, 분당 전송량 " + mbytesPerMin + "MB)";
        boolean managed = networkPaths.retirePort(
                jdbcTemplate.queryForObject("select vm_id from port_mappings where id = ?",
                        Long.class, mappingId),
                mappingId, VmNetworkPathOperationStore.Action.SUSPEND);
        long generation = managed ? jdbcTemplate.queryForObject(
                "select last_change_generation from port_mappings where id = ?",
                Long.class, mappingId) : relayGenerations.bump(relayId);
        int suspended = jdbcTemplate.update("""
                update port_mappings
                   set status = 'SUSPENDED', suspended_reason = ?, suspended_by = null,
                       last_change_generation = ?, updated_at = now()
                 where id = ? and status = 'ACTIVE'
                """, reason, generation, mappingId);
        if (suspended != 1) {
            return; // already suspended (e.g. duplicated report row)
        }
        // v.public_id, not v.id: the notification's link path is built from
        // this map by string concatenation, so a numeric id here renders a
        // console link that resolves to nothing.
        Map<String, Object> context = jdbcTemplate.queryForObject("""
                select v.public_id as vm_public_id, v.name as vm_name, m.proto, m.public_port,
                       m.public_id as mapping_public_id
                  from port_mappings m join vms v on v.id = m.vm_id where m.id = ?
                """, (rs, rowNum) -> Map.of(
                        "vmId", rs.getObject("vm_public_id", java.util.UUID.class),
                        "vmName", rs.getString("vm_name"),
                        "proto", rs.getString("proto"), "publicPort", rs.getInt("public_port"),
                        "mappingPublicId", rs.getObject("mapping_public_id", java.util.UUID.class)),
                mappingId);
        java.util.UUID mappingPublicId = (java.util.UUID) context.get("mappingPublicId");
        Map<String, Object> args = new LinkedHashMap<>(context);
        args.remove("mappingPublicId");
        args.put("reason", reason);
        notificationService.publish(notificationService.sysAdminIds(),
                NotificationEvent.PORT_MAPPING_SUSPENDED, args, "pm_auto_suspend:" + mappingId);
        auditService.recordAfterCommit(null, AuditService.ACTOR_ROLE_RELAY, AuditService.PORT_MAPPING_SUSPEND,
                "port_mapping", mappingPublicId, Map.of("auto", true, "relayId", relayPublicId(relayId),
                        "connsPerMin", connsPerMin, "mbytesPerMin", mbytesPerMin,
                        "connsLimit", connsLimit, "mbytesLimit", mbytesLimit), null);
        log.warn("port mapping {} auto-suspended (conns/min {} vs {}, MB/min {} vs {})",
                mappingId, connsPerMin, connsLimit, mbytesPerMin, mbytesLimit);
    }

    /** The relay's public identifier, for the audit trail's target column. */
    private java.util.UUID relayPublicId(long relayId) {
        return jdbcTemplate.queryForObject("select public_id from relays where id = ?",
                java.util.UUID.class, relayId);
    }

    private void processRetirementHandshake(long relayId, RelaySyncRequest request,
            Set<String> capabilities) {
        boolean capable = capabilities.contains(RETIREMENT_CAPABILITY);
        RetirementRelay relay = jdbcTemplate.query("""
                select retirement_armed, retirement_ledger_id, mapping_id_high_water,
                       flow_mark_high_water, reported_mapping_id_high_water,
                       reported_flow_mark_high_water,
                       reported_managed_generation_high_water,
                       reported_retirement_high_water, acknowledged_retirement_high_water,
                       mark_namespace_ready
                  from relays where id = ? for update
                """, rs -> rs.next() ? new RetirementRelay(rs.getBoolean(1),
                        rs.getObject(2, java.util.UUID.class), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8),
                        rs.getLong(9), rs.getBoolean(10)) : null, relayId);
        if (relay == null) {
            throw retirementUnavailable("릴레이 retirement 상태를 찾을 수 없습니다.");
        }
        if (!capable) {
            if (relay.armed()) {
                throw retirementUnavailable(
                        "retirement이 활성화된 릴레이가 mapping-retirement-v1을 보고하지 않았습니다.");
            }
            return;
        }
        if (request.retirementLedgerId() == null || request.mappingIdHighWater() == null
                || request.flowMarkHighWater() == null
                || request.managedGenerationHighWater() == null
                || request.retirementHighWater() == null
                || request.retirementReceipts() == null) {
            throw retirementUnavailable("retirement capability 보고가 완전하지 않습니다.");
        }
        if (relay.ledgerId() != null && !relay.ledgerId().equals(request.retirementLedgerId())) {
            throw retirementUnavailable("릴레이 retirement ledger identity가 변경되었습니다.");
        }
        if (request.mappingIdHighWater() < relay.reportedMappingHighWater()
                || request.flowMarkHighWater() < relay.reportedFlowHighWater()
                || request.managedGenerationHighWater()
                        < relay.reportedManagedGenerationHighWater()
                || request.retirementHighWater() < relay.reportedRetirementHighWater()) {
            throw retirementUnavailable("릴레이 retirement high-watermark가 역행했습니다.");
        }
        if (relay.armed() && !relay.namespaceReady()) {
            throw retirementUnavailable("릴레이 conntrack mark namespace 확인이 완료되지 않았습니다.");
        }
        if (relay.armed()) {
            Long legacy = jdbcTemplate.queryForObject("""
                    select count(*) from port_mappings
                     where relay_id = ? and delivery_state = 'LEGACY'
                       and status <> 'REMOVING'
                    """, Long.class, relayId);
            if (legacy != null && legacy > 0) {
                throw retirementUnavailable(
                        "표시되지 않은 legacy mapping이 있어 retirement activation을 거부했습니다.");
            }
        }
        long maxReceiptMark = 0;
        long maxReceiptGeneration = 0;
        long maxReceiptMappingId = 0;
        for (RelaySyncRequest.RetirementReceipt receipt : request.retirementReceipts()) {
            if (!"CLEARED".equals(receipt.state())
                    || receipt.tupleHash() == null
                    || !receipt.tupleHash().matches("[0-9a-f]{64}")) {
                throw retirementUnavailable("retirement receipt 형식이 올바르지 않습니다.");
            }
            maxReceiptMark = Math.max(maxReceiptMark, receipt.flowMark());
            maxReceiptGeneration = Math.max(maxReceiptGeneration, receipt.generation());
            maxReceiptMappingId = Math.max(maxReceiptMappingId, receipt.mappingId());
            int updated = jdbcTemplate.update("""
                    update relay_mapping_retirements
                       set cleared_at = coalesce(cleared_at, now()),
                           receipt_generation = ?, updated_at = now()
                     where id = ? and relay_id = ? and mapping_id = ?
                       and generation = ? and flow_mark = ? and tuple_hash = ?
                    """, receipt.generation(), receipt.retirementId(), relayId,
                    receipt.mappingId(), receipt.generation(), receipt.flowMark(),
                    receipt.tupleHash());
            if (updated == 0) {
                Long collision = jdbcTemplate.queryForObject("""
                        select count(*) from port_mappings
                         where relay_id = ? and status <> 'REMOVING'
                           and (consumer_mapping_id = ? or flow_mark = ?)
                        """, Long.class, relayId, receipt.mappingId(), receipt.flowMark());
                if (collision != null && collision > 0) {
                    throw retirementUnavailable(
                            "복원된 DB가 consumer ledger에서 퇴역한 mapping/mark를 다시 활성화했습니다.");
                }
                log.warn("relay {} reports durable retirement {} absent from this DB; "
                        + "high-watermark retained", relayId, receipt.retirementId());
            }
        }
        if (request.mappingIdHighWater() < maxReceiptMappingId
                || request.flowMarkHighWater() < maxReceiptMark
                || request.retirementHighWater() < maxReceiptGeneration) {
            throw retirementUnavailable("retirement receipt가 보고 high-watermark를 초과합니다.");
        }
        jdbcTemplate.update("""
                update relays
                   set retirement_ledger_id = coalesce(retirement_ledger_id, ?),
                       mapping_id_high_water = greatest(mapping_id_high_water, ?),
                       flow_mark_high_water = greatest(flow_mark_high_water, ?),
                       reported_mapping_id_high_water = ?,
                       reported_flow_mark_high_water = ?,
                       reported_managed_generation_high_water = ?,
                       reported_retirement_high_water = ?, retirement_observed_at = now(),
                       mapping_generation = case
                           when mapping_generation < ? then ? + 1
                           else mapping_generation end,
                       updated_at = now()
                 where id = ?
                """, request.retirementLedgerId(), request.mappingIdHighWater(),
                request.flowMarkHighWater(), request.mappingIdHighWater(),
                request.flowMarkHighWater(), request.managedGenerationHighWater(),
                request.retirementHighWater(), request.managedGenerationHighWater(),
                request.managedGenerationHighWater(), relayId);
        advanceRetirementAcknowledgement(relayId, relay.acknowledgedRetirementHighWater());
    }

    /** Advances only across the ordered prefix for which every exact tuple is CLEARED. */
    private void advanceRetirementAcknowledgement(long relayId, long previous) {
        long acknowledged = previous;
        for (RetirementClearance row : jdbcTemplate.query("""
                select generation, cleared_at is not null
                  from relay_mapping_retirements
                 where relay_id = ? and generation > ?
                 order by generation
                """, (rs, ignored) -> new RetirementClearance(rs.getLong(1), rs.getBoolean(2)),
                relayId, previous)) {
            if (!row.cleared()) {
                break;
            }
            acknowledged = row.generation();
        }
        if (acknowledged > previous) {
            jdbcTemplate.update("""
                    update relays set acknowledged_retirement_high_water = ?, updated_at = now()
                     where id = ? and acknowledged_retirement_high_water = ?
                    """, acknowledged, relayId, previous);
            relayGenerations.bump(relayId);
        }
    }

    private static ApiException retirementUnavailable(String detail) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.SOURCE_POLICY_UNAVAILABLE,
                "릴레이 retirement 상태를 확인할 수 없습니다", detail);
    }

    private record RetirementRelay(boolean armed, java.util.UUID ledgerId,
            long mappingHighWater, long flowHighWater, long reportedMappingHighWater,
            long reportedFlowHighWater, long reportedManagedGenerationHighWater,
            long reportedRetirementHighWater,
            long acknowledgedRetirementHighWater, boolean namespaceReady) {
    }

    private record RetirementClearance(long generation, boolean cleared) {
    }

    // ── snapshot ─────────────────────────────────────────────────────────────

    private RelaySyncResponse readSnapshot(long relayId, long validatedApplied,
            boolean forceFull, Set<String> currentCapabilities) {
        SnapshotData snapshot = jdbcTemplate.query(SNAPSHOT_SQL, rs -> {
            long generation = 0;
            boolean retirementArmed = false;
            long acknowledgedRetirementHighWater = 0;
            List<RelaySyncResponse.MappingSnapshot> mappings = new ArrayList<>();
            while (rs.next()) {
                generation = rs.getLong("mapping_generation");
                retirementArmed = rs.getBoolean("retirement_armed");
                acknowledgedRetirementHighWater =
                        rs.getLong("acknowledged_retirement_high_water");
                long mappingId = rs.getLong("id");
                if (rs.wasNull()) {
                    continue; // left-join row of a relay with no active mapping
                }
                String targetAddr = rs.getString("target_addr");
                if (targetAddr == null) {
                    // Should be unreachable (teardown deletes mappings in the
                    // same tx as the IP release) — never ship a rule without a
                    // live target.
                    log.warn("port mapping {} has no live target address — dropped from snapshot",
                            mappingId);
                    continue;
                }
                long mappingRowId = rs.getLong("row_id");
                SourcePolicyWire sourcePolicy = sourcePolicies.portMapping(mappingRowId,
                        rs.getObject("source_policy_generation") != null).orElse(null);
                if (sourcePolicy != null
                        && !networkPolicyCapability.sourceAclAvailable(currentCapabilities)) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            ErrorCodes.SOURCE_POLICY_UNAVAILABLE,
                            "릴레이 출발지 정책을 적용할 수 없습니다",
                            "현재 relay-agent가 source-acl-v1 기능을 보고하지 않았습니다.");
                }
                Long flowMark = rs.getObject("flow_mark", Long.class);
                if (retirementArmed && (flowMark == null || flowMark <= 0
                        || flowMark > UINT32_MAX)) {
                    throw retirementUnavailable("managed mapping flowMark가 올바르지 않습니다.");
                }
                mappings.add(new RelaySyncResponse.MappingSnapshot(mappingId,
                        rs.getString("proto"), rs.getInt("public_port"), targetAddr,
                        rs.getInt("target_port"),
                        rs.getObject("ct_max", Integer.class),
                        rs.getObject("new_conn_rate", Integer.class),
                        rs.getObject("new_conn_burst", Integer.class),
                        rs.getObject("per_source_rate", Integer.class),
                        rs.getObject("per_source_burst", Integer.class), sourcePolicy, flowMark));
            }
            return new SnapshotData(generation, retirementArmed,
                    acknowledgedRetirementHighWater, mappings);
        }, relayId);
        boolean retirementCapable = currentCapabilities.contains(RETIREMENT_CAPABILITY);
        List<RelaySyncResponse.RetirementSnapshot> retirements = snapshot.retirementArmed()
                ? jdbcTemplate.query("""
                        select id, mapping_id, generation, lower(protocol), public_port,
                               host(target_addr), target_port, flow_mark, tuple_hash
                          from relay_mapping_retirements
                         where relay_id = ? and generation > ?
                         order by retirement_sequence
                        """, (rs, row) -> new RelaySyncResponse.RetirementSnapshot(
                                rs.getObject(1, java.util.UUID.class), rs.getLong(2), rs.getLong(3),
                                rs.getString(4), rs.getInt(5), rs.getString(6), rs.getInt(7),
                                rs.getLong(8), rs.getString(9)), relayId,
                        snapshot.acknowledgedRetirementHighWater())
                : null;
        if (snapshot.retirementArmed() && retirementCapable) {
            // Managed consumers persist a canonical hash over the complete typed
            // snapshot. Even at an unchanged generation, omission would make
            // retirements/ACK look like a changed but incomplete snapshot.
            return new RelaySyncResponse(snapshot.generation(), snapshot.mappings(),
                    retirements, snapshot.acknowledgedRetirementHighWater());
        }
        if (!forceFull && validatedApplied == snapshot.generation()) {
            return new RelaySyncResponse(snapshot.generation(), null, null, null);
        }
        return new RelaySyncResponse(snapshot.generation(), snapshot.mappings(), null, null);
    }

    private record SnapshotData(long generation, boolean retirementArmed,
            long acknowledgedRetirementHighWater,
            List<RelaySyncResponse.MappingSnapshot> mappings) {
    }

    private List<SanitizedError> sanitizeErrors(long relayId,
            List<RelaySyncRequest.ReportedMappingError> reported) {
        if (reported == null || reported.isEmpty()) {
            return List.of();
        }
        Boolean retirementArmed = jdbcTemplate.queryForObject(
                "select retirement_armed from relays where id = ?", Boolean.class, relayId);
        List<SanitizedError> sanitized = new ArrayList<>(reported.size());
        for (RelaySyncRequest.ReportedMappingError error : reported) {
            String message = Texts.sanitizeReported(error.message(), REPORTED_TEXT_MAX);
            Long mappingId = error.mappingId() == null ? null : jdbcTemplate.query("""
                    select id from port_mappings
                     where relay_id = ? and coalesce(consumer_mapping_id, id) = ?
                    """, rs -> rs.next() ? rs.getLong(1) : null, relayId, error.mappingId());
            if (mappingId == null && !Boolean.TRUE.equals(retirementArmed)) {
                mappingId = error.mappingId();
            }
            sanitized.add(new SanitizedError(mappingId,
                    message == null ? "" : message));
        }
        return sanitized;
    }

    /** Persisted shape of one sanitized agent error (relays.last_error JSON). */
    record SanitizedError(Long mappingId, String message) {
    }

    private record GenerationState(long applied, long current) {
    }

    /** Non-negative raw readings (null and negative both collapse to 0). */
    private record Raw(long newConns, long inPackets, long inBytes, long outPackets,
            long outBytes, long rateDropped, long connDropped, long perSourceDropped) {

        /** Sanity ceiling on any single raw reading (2^53). */
        static final long SANITY_MAX = 1L << 53;

        static Raw of(RelaySyncRequest.ReportedMappingCounters row) {
            return new Raw(nn(row.newConns()), nn(row.inPackets()), nn(row.inBytes()),
                    nn(row.outPackets()), nn(row.outBytes()), nn(row.rateDropped()),
                    nn(row.connDropped()), nn(row.perSourceDropped()));
        }

        private static long nn(Long value) {
            return value == null || value < 0 ? 0 : value;
        }

        long max() {
            return Math.max(Math.max(Math.max(newConns, inPackets),
                    Math.max(inBytes, outPackets)), Math.max(Math.max(outBytes, rateDropped),
                    Math.max(connDropped, perSourceDropped)));
        }

        /** Any reading past the ceiling — the whole row is discarded + audited. */
        boolean beyondSanity() {
            return max() > SANITY_MAX;
        }

        long drops() {
            return rateDropped + connDropped + perSourceDropped;
        }

        /** Any reading below its stored last value = the agent restarted. */
        boolean anyBelow(CounterRow last) {
            return newConns < last.lastNewConns() || inPackets < last.lastInPackets()
                    || inBytes < last.lastInBytes() || outPackets < last.lastOutPackets()
                    || outBytes < last.lastOutBytes() || rateDropped < last.lastRateDropped()
                    || connDropped < last.lastConnDropped()
                    || perSourceDropped < last.lastPerSourceDropped();
        }
    }

    private record CounterRow(long connTotal, long bytesTotal, long dropTotal, long lastNewConns,
            long lastInPackets, long lastInBytes, long lastOutPackets, long lastOutBytes,
            long lastRateDropped, long lastConnDropped, long lastPerSourceDropped,
            Instant lastDeltaAt) {

        static CounterRow of(java.sql.ResultSet rs) throws java.sql.SQLException {
            OffsetDateTime lastDeltaAt = rs.getObject("last_delta_at", OffsetDateTime.class);
            return new CounterRow(rs.getLong("conn_total"), rs.getLong("bytes_total"),
                    rs.getLong("drop_total"), rs.getLong("last_new_conns"),
                    rs.getLong("last_in_packets"), rs.getLong("last_in_bytes"),
                    rs.getLong("last_out_packets"), rs.getLong("last_out_bytes"),
                    rs.getLong("last_rate_dropped"), rs.getLong("last_conn_dropped"),
                    rs.getLong("last_per_source_dropped"),
                    lastDeltaAt == null ? null : lastDeltaAt.toInstant());
        }

        long lastDrops() {
            return lastRateDropped + lastConnDropped + lastPerSourceDropped;
        }
    }
}
