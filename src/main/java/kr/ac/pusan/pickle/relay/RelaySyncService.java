package kr.ac.pusan.pickle.relay;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.relay.dto.RelaySyncRequest;
import kr.ac.pusan.pickle.relay.dto.RelaySyncResponse;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * a violation is audited, the reported value is discarded, and the relay gets
 * the full snapshot so a confused (or lying) agent converges instead of
 * wedging.</p>
 */
@Service
public class RelaySyncService {

    /** Server-side cap on any agent-reported string persisted or audited. */
    static final int REPORTED_TEXT_MAX = 1024;

    private static final Logger log = LoggerFactory.getLogger(RelaySyncService.class);

    /**
     * Generation + snapshot in one statement (single MVCC view). SUSPENDED
     * mappings are excluded (the agent only sees desired state), and the
     * target address is resolved live from the VM's own ALLOCATED
     * ip_allocations row — never stored on the mapping.
     */
    private static final String SNAPSHOT_SQL = """
            select r.mapping_generation, m.id, lower(m.proto) as proto,
                   m.public_port, m.target_port,
                   m.ct_max, m.new_conn_rate, m.new_conn_burst,
                   m.per_source_rate, m.per_source_burst,
                   host(a.ip) as target_addr
              from relays r
              left join port_mappings m on m.relay_id = r.id and m.status = 'ACTIVE'
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
    private final ObjectMapper objectMapper;

    public RelaySyncService(JdbcTemplate jdbcTemplate, RelayGenerations relayGenerations,
            SettingsService settingsService, NotificationService notificationService,
            AuditService auditService, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.relayGenerations = relayGenerations;
        this.settingsService = settingsService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RelaySyncResponse sync(long relayId, RelaySyncRequest request) {
        GenerationState state = jdbcTemplate.queryForObject("""
                select applied_generation, mapping_generation from relays where id = ?
                """, (rs, rowNum) -> new GenerationState(rs.getLong(1), rs.getLong(2)), relayId);

        String agentVersion = Texts.sanitizeReported(request.agentVersion(), REPORTED_TEXT_MAX);
        List<SanitizedError> errors = sanitizeErrors(request.lastError());
        String lastErrorJson = errors.isEmpty() ? null : objectMapper.writeValueAsString(errors);

        // appliedGeneration must stay in [stored, current] — anything else is
        // impossible for an honest agent (@Min(0) already rejected negatives).
        long reported = request.appliedGeneration();
        boolean violation = reported > state.current() || reported < state.applied();
        long validated = violation ? state.applied() : reported;
        if (violation) {
            // Direct record (not after-commit): a security signal, keep it even
            // if something later in this tx were to fail.
            auditService.record(null, AuditService.ACTOR_ROLE_RELAY,
                    AuditService.RELAY_SYNC_VIOLATION, "relay", relayId,
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

        return readSnapshot(relayId, validated, violation);
    }

    // ── counters (reset-aware) ───────────────────────────────────────────────

    /**
     * Accumulates raw (cumulative since agent start) readings into totals.
     * Any raw value below its stored last reading means the agent restarted:
     * the row re-baselines (delta = raw) — a decrease is NEVER a negative
     * delta. Per-minute rates against {@code last_delta_at} feed the
     * auto-suspend thresholds; a breach suspends the mapping in this same
     * transaction, so the snapshot answered below already excludes it.
     */
    private void accumulateCounters(long relayId,
            List<RelaySyncRequest.ReportedMappingCounters> reportedRows) {
        long connsPerMinLimit = settingsService.integer(
                SettingsService.PORT_FORWARD_SUSPEND_CONNS_PER_MIN, 6000);
        long mbytesPerMinLimit = settingsService.integer(
                SettingsService.PORT_FORWARD_SUSPEND_MBYTES_PER_MIN, 1000);
        // A report row is only credited to a mapping this relay owns — one
        // batch read of the relay's OWN ids; foreign mappingIds never even
        // reach a query parameter, they simply miss this set.
        java.util.Set<Long> ownedIds = new java.util.HashSet<>(jdbcTemplate.queryForList(
                "select id from port_mappings where relay_id = ?", Long.class, relayId));
        Instant now = Instant.now();
        for (RelaySyncRequest.ReportedMappingCounters reportedRow : reportedRows) {
            if (reportedRow.mappingId() == null || !ownedIds.contains(reportedRow.mappingId())) {
                continue;
            }
            long mappingId = reportedRow.mappingId();
            Raw raw = Raw.of(reportedRow);
            if (raw.beyondSanity()) {
                // Insane magnitude (> 2^53): discard the whole reading like a
                // reset to nothing — totals never ingest it, the stored
                // baseline stays put, and the event is audited. Bounds the
                // bigint totals against a lying or corrupted agent.
                auditService.record(null, AuditService.ACTOR_ROLE_RELAY,
                        AuditService.RELAY_SYNC_VIOLATION, "relay", relayId,
                        Map.of("kind", "counter_sanity", "mappingId", mappingId,
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
        String reason = "트래픽 임계값 초과로 자동 정지 (분당 신규 연결 " + connsPerMin
                + "건, 분당 전송량 " + mbytesPerMin + "MB)";
        long generation = relayGenerations.bump(relayId);
        int suspended = jdbcTemplate.update("""
                update port_mappings
                   set status = 'SUSPENDED', suspended_reason = ?, suspended_by = null,
                       last_change_generation = ?, updated_at = now()
                 where id = ? and status = 'ACTIVE'
                """, reason, generation, mappingId);
        if (suspended != 1) {
            return; // already suspended (e.g. duplicated report row)
        }
        Map<String, Object> context = jdbcTemplate.queryForObject("""
                select m.vm_id, v.name as vm_name, m.proto, m.public_port
                  from port_mappings m join vms v on v.id = m.vm_id where m.id = ?
                """, (rs, rowNum) -> Map.of(
                        "vmId", rs.getLong("vm_id"), "vmName", rs.getString("vm_name"),
                        "proto", rs.getString("proto"), "publicPort", rs.getInt("public_port")),
                mappingId);
        Map<String, Object> args = new LinkedHashMap<>(context);
        args.put("reason", reason);
        notificationService.publish(notificationService.sysAdminIds(),
                NotificationEvent.PORT_MAPPING_SUSPENDED, args, "pm_auto_suspend:" + mappingId);
        auditService.recordAfterCommit(null, AuditService.ACTOR_ROLE_RELAY, AuditService.PORT_MAPPING_SUSPEND,
                "port_mapping", mappingId, Map.of("auto", true, "relayId", relayId,
                        "connsPerMin", connsPerMin, "mbytesPerMin", mbytesPerMin,
                        "connsLimit", connsLimit, "mbytesLimit", mbytesLimit), null);
        log.warn("port mapping {} auto-suspended (conns/min {} vs {}, MB/min {} vs {})",
                mappingId, connsPerMin, connsLimit, mbytesPerMin, mbytesLimit);
    }

    // ── snapshot ─────────────────────────────────────────────────────────────

    private RelaySyncResponse readSnapshot(long relayId, long validatedApplied,
            boolean forceFull) {
        return jdbcTemplate.query(SNAPSHOT_SQL, rs -> {
            long generation = 0;
            List<RelaySyncResponse.MappingSnapshot> mappings = new ArrayList<>();
            while (rs.next()) {
                generation = rs.getLong("mapping_generation");
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
                mappings.add(new RelaySyncResponse.MappingSnapshot(mappingId,
                        rs.getString("proto"), rs.getInt("public_port"), targetAddr,
                        rs.getInt("target_port"),
                        rs.getObject("ct_max", Integer.class),
                        rs.getObject("new_conn_rate", Integer.class),
                        rs.getObject("new_conn_burst", Integer.class),
                        rs.getObject("per_source_rate", Integer.class),
                        rs.getObject("per_source_burst", Integer.class)));
            }
            if (!forceFull && validatedApplied == generation) {
                return new RelaySyncResponse(generation, null); // tiny answer
            }
            return new RelaySyncResponse(generation, mappings);
        }, relayId);
    }

    private List<SanitizedError> sanitizeErrors(
            List<RelaySyncRequest.ReportedMappingError> reported) {
        if (reported == null || reported.isEmpty()) {
            return List.of();
        }
        List<SanitizedError> sanitized = new ArrayList<>(reported.size());
        for (RelaySyncRequest.ReportedMappingError error : reported) {
            String message = Texts.sanitizeReported(error.message(), REPORTED_TEXT_MAX);
            sanitized.add(new SanitizedError(error.mappingId(),
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
