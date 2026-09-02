package kr.ac.pusan.pickle.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.LlmActiveObservationResponse;
import kr.ac.pusan.pickle.admin.dto.LlmActiveProbeStatus;
import kr.ac.pusan.pickle.admin.dto.LlmCatalogObservationResponse;
import kr.ac.pusan.pickle.admin.dto.LlmCatalogStatus;
import kr.ac.pusan.pickle.admin.dto.LlmGatewayReportState;
import kr.ac.pusan.pickle.admin.dto.LlmGatewayStatusResponse;
import kr.ac.pusan.pickle.admin.dto.LlmLocalRejectionMetricResponse;
import kr.ac.pusan.pickle.admin.dto.LlmMetricsResponse;
import kr.ac.pusan.pickle.admin.dto.LlmPassiveObservationResponse;
import kr.ac.pusan.pickle.admin.dto.LlmStatusResponse;
import kr.ac.pusan.pickle.admin.dto.LlmUpstreamAvailability;
import kr.ac.pusan.pickle.admin.dto.LlmUpstreamKind;
import kr.ac.pusan.pickle.admin.dto.LlmUpstreamMetricResponse;
import kr.ac.pusan.pickle.admin.dto.LlmUpstreamReportState;
import kr.ac.pusan.pickle.admin.dto.LlmUpstreamStatusResponse;
import kr.ac.pusan.pickle.orgs.AdminOrgScope;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Read-only upstream current state and accurately labelled raw-event aggregates. */
@Service
public class AdminLlmObservabilityService {

    /** Six missed sync polls: enough to distinguish a deployment pause from one delayed call. */
    static final long REPORT_STALE_SECONDS = 30;

    /** Real-request evidence older than the longest probe interval times three is historical. */
    static final long PASSIVE_FRESH_SECONDS = 15 * 60;

    private static final Set<String> NON_OUTAGE_FAILURES =
            Set.of("REQUEST_REJECTED", "CREDIT_EXHAUSTED",
                    "KEY_CREDENTIAL_ERROR", "KEY_THROTTLED");

    /**
     * Only errors that are unambiguously decided before an upstream call.
     * Historical rows from before failure attribution was fixed also have a
     * null upstream ref, so ref-null by itself is not a local-rejection fact.
     */
    private static final String LOCAL_REJECTION_TYPES = """
            'missing_api_key', 'invalid_api_key', 'api_key_expired', 'api_key_revoked',
            'account_suspended', 'quota_exhausted', 'credit_unavailable',
            'credit_pending', 'credit_exhausted', 'service_disabled', 'model_not_found',
            'model_not_allowed', 'rate_limit_requests', 'rate_limit_tokens',
            'rate_limit_concurrency', 'server_busy', 'request_too_large',
            'input_too_long', 'output_limit_exceeded', 'invalid_json',
            'unsupported_parameter', 'invalid_parameter_value', 'missing_parameter',
            'unknown_endpoint', 'method_not_allowed'
            """;

    private final JdbcTemplate jdbcTemplate;
    private final OrgRepository orgRepository;
    private final ObjectMapper objectMapper;

    public AdminLlmObservabilityService(JdbcTemplate jdbcTemplate, OrgRepository orgRepository,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.orgRepository = orgRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public LlmStatusResponse status(AuthenticatedUser actor, @Nullable UUID orgId) {
        OrgScope scope = scope(actor, orgId);
        Instant now = Instant.now();
        GatewayRow gateway = gateway();
        LlmGatewayReportState gatewayReportState = gatewayReportState(gateway, now);
        boolean sysDiagnostics = !actor.role().isOrgTier();
        Set<String> allowedRefs = allowedRefs(scope);

        List<LlmUpstreamStatusResponse> upstreams = new ArrayList<>();
        jdbcTemplate.query("""
                select u.public_id, u.ref as registry_ref, u.kind, u.display_name,
                       o.public_id as org_public_id, u.dedicated, u.enabled, u.passthrough,
                       s.ref as state_ref, s.configured, s.last_reported_at,
                       s.passive_last_attempt_at, s.passive_last_success_at,
                       s.passive_last_failure_at, s.passive_last_failure_type,
                       s.passive_consecutive_failures, s.passive_cooldown_until,
                       s.active_last_attempt_at, s.active_last_success_at,
                       s.active_last_failure_at, s.active_status, s.active_failure_type,
                       s.active_probe_interval_seconds, s.active_latency_ms,
                       s.active_model_count,
                       s.active_consecutive_failures, s.catalog_status,
                       s.catalog_expected_model_count, s.catalog_missing_model_count,
                       s.catalog_unexpected_model_count,
                       s.catalog_missing_public_models
                  from llm_upstreams u
                  full outer join llm_upstream_state s on s.ref = lower(u.ref)
                  left join orgs o on o.id = u.org_id
                 order by coalesce(u.display_name, s.ref), coalesce(u.ref, s.ref)
                """, rs -> {
            String actualRef = rs.getString("registry_ref") != null
                    ? rs.getString("registry_ref").toLowerCase(Locale.ROOT)
                    : rs.getString("state_ref");
            if (allowedRefs != null && !allowedRefs.contains(actualRef)) {
                return;
            }
            UpstreamRow row = upstreamRow(rs);
            LlmUpstreamReportState reportState = upstreamReportState(row, gateway,
                    gatewayReportState, now);
            LlmUpstreamAvailability availability = availability(row, reportState,
                    gateway, gatewayReportState, now);
            upstreams.add(toStatus(row, reportState, availability, sysDiagnostics, now));
        });

        return new LlmStatusResponse(now,
                gatewayResponse(gateway, gatewayReportState, sysDiagnostics, now),
                List.copyOf(upstreams));
    }

    /** Shared global gateway read with the same SYS diagnostics redaction as the status API. */
    LlmGatewayStatusResponse gatewayStatus(AuthenticatedUser actor, Instant now) {
        GatewayRow row = gateway();
        return gatewayResponse(row, gatewayReportState(row, now),
                !actor.role().isOrgTier(), now);
    }

    /**
     * Summary, upstream groups and local rejections are three SQL statements.
     * One repeatable-read snapshot prevents an ingest commit between them from
     * making the response's totals disagree with its breakdowns.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LlmMetricsResponse metrics(AuthenticatedUser actor, @Nullable UUID orgId, int days) {
        OrgScope scope = scope(actor, orgId);
        Instant to = Instant.now();
        Instant from = to.minus(days, ChronoUnit.DAYS);
        boolean sysDiagnostics = !actor.role().isOrgTier();
        Set<String> visibleRefs = sysDiagnostics ? null : allowedRefs(scope);
        String scopeClause = scope.isUnrestricted() ? "" : " and " + scope.guard("k.org_id");

        List<Object> summaryArgs = timeAndScopeArgs(from, to, scope);
        MetricSummary summary = jdbcTemplate.queryForObject("""
                select count(*) as total,
                       count(*) filter (where e.upstream_ref is not null) as attributed,
                       count(*) filter (where e.attempts is not null and e.attempts > 0)
                           as attempts_known,
                       count(*) filter (where e.estimated) as estimated
                  from llm_usage_events e
                  left join llm_api_keys k on k.id = e.key_id
                 where e.requested_at >= ? and e.requested_at < ?
                """ + scopeClause, (rs, rowNum) -> new MetricSummary(
                        rs.getLong("total"), rs.getLong("attributed"),
                        rs.getLong("attempts_known"), rs.getLong("estimated")),
                summaryArgs.toArray());

        List<Object> groupArgs = timeAndScopeArgs(from, to, scope);
        List<LlmUpstreamMetricResponse> rawUpstreams = jdbcTemplate.query("""
                select u.public_id, u.display_name, lower(e.upstream_ref) as upstream_ref,
                       count(*) as final_outcomes,
                       count(*) filter (where e.status = 'OK') as succeeded,
                       count(*) filter (where e.status in ('TIMEOUT', 'UPSTREAM_ERROR'))
                           as timeout_or_error,
                       coalesce(sum(e.input_tokens), 0) as input_tokens,
                       coalesce(sum(e.output_tokens), 0) as output_tokens,
                       count(*) filter (where e.attempts is not null and e.attempts > 0)
                           as attempts_known,
                       count(*) filter (where e.attempts > 1) as multi_attempt,
                       coalesce(sum(e.attempts) filter
                           (where e.attempts is not null and e.attempts > 0), 0) as attempts_sum,
                       count(*) filter (where e.status = 'OK') as latency_samples,
                       percentile_disc(0.50) within group (order by e.latency_ms)
                           filter (where e.status = 'OK') as latency_p50,
                       percentile_disc(0.95) within group (order by e.latency_ms)
                           filter (where e.status = 'OK') as latency_p95,
                       percentile_disc(0.99) within group (order by e.latency_ms)
                           filter (where e.status = 'OK') as latency_p99
                  from llm_usage_events e
                  left join llm_api_keys k on k.id = e.key_id
                  left join llm_upstreams u on lower(u.ref) = lower(e.upstream_ref)
                 where e.requested_at >= ? and e.requested_at < ?
                   and e.upstream_ref is not null
                """ + scopeClause + """
                 group by u.public_id, u.display_name, lower(e.upstream_ref)
                 order by final_outcomes desc, lower(e.upstream_ref)
                """, (rs, rowNum) -> upstreamMetric(rs, true), groupArgs.toArray());
        List<LlmUpstreamMetricResponse> upstreams = rawUpstreams.stream()
                .filter(metric -> visibleRefs == null || visibleRefs.contains(metric.ref()))
                .map(metric -> sysDiagnostics ? metric : redactMetricRef(metric))
                .toList();

        List<Object> rejectionArgs = timeAndScopeArgs(from, to, scope);
        List<LlmLocalRejectionMetricResponse> localRejections = jdbcTemplate.query("""
                select e.error_type, count(*) as requests
                  from llm_usage_events e
                  left join llm_api_keys k on k.id = e.key_id
                 where e.requested_at >= ? and e.requested_at < ?
                   and e.upstream_ref is null
                   and e.error_type in (""" + LOCAL_REJECTION_TYPES + ")"
                + scopeClause + """
                 group by e.error_type
                 order by requests desc, e.error_type
                """, (rs, rowNum) -> new LlmLocalRejectionMetricResponse(
                        rs.getString("error_type"), rs.getLong("requests")),
                rejectionArgs.toArray());

        MetricSummary safe = summary == null ? new MetricSummary(0, 0, 0, 0) : summary;
        return new LlmMetricsResponse(from, to, safe.total(), safe.attributed(),
                ratio(safe.attributed(), safe.total()), safe.attemptsKnown(),
                ratio(safe.attemptsKnown(), safe.total()), safe.estimated(),
                ratio(safe.estimated(), safe.total()), upstreams, localRejections);
    }

    private OrgScope scope(AuthenticatedUser actor, @Nullable UUID orgId) {
        Long requested = orgId == null ? null
                : orgRepository.findByPublicId(orgId).map(Org::getId).orElse(null);
        return AdminOrgScope.read(actor, orgId, requested);
    }

    /** Null means unrestricted; an empty set is a real scope matching nothing. */
    private @Nullable Set<String> allowedRefs(OrgScope scope) {
        if (scope.isUnrestricted()) {
            return null;
        }
        if (scope.orgIds().isEmpty()) {
            return Set.of();
        }
        Set<String> refs = new HashSet<>();
        String array = scope.arrayParam();
        jdbcTemplate.query("""
                select lower(ref) as ref
                  from llm_upstreams
                 where """ + scope.guard("org_id") + """
                union
                select lower(u.ref) as ref
                  from llm_upstreams u
                 where u.org_id is null and not u.dedicated and u.enabled
                   and (u.passthrough or exists (
                       select 1 from llm_models m
                        where m.enabled and m.visibility = 'PUBLIC'
                          and (lower(m.upstream_ref) = lower(u.ref)
                            or lower(m.fallback_ref) = lower(u.ref))))
                union
                select distinct lower(e.upstream_ref) as ref
                  from llm_usage_events e
                  join llm_api_keys k on k.id = e.key_id
                  join llm_upstreams u on lower(u.ref) = lower(e.upstream_ref)
                 where u.org_id is null and not u.dedicated
                   and e.requested_at >= now() - interval '31 days'
                   and """ + scope.guard("k.org_id"), rs -> {
                       refs.add(rs.getString("ref"));
                   },
                array, array, array, array);
        return refs;
    }

    private GatewayRow gateway() {
        List<GatewayRow> rows = jdbcTemplate.query("""
                select generation, applied_generation, supported_format, agent_version,
                       started_at, in_flight, max_in_flight, rejected_entries,
                       reload_failures, last_error, bodies_dropped, usage_ship_failures,
                       spool_write_failures, last_contact_at, upstream_observation_format,
                       last_usage_ship_success_at, oldest_unshipped_event_at,
                       queued_usage_events, queued_usage_bytes, usage_queue_observed_at,
                       usage_queue_scan_failures
                  from llm_gateway_state where id
                """, (rs, rowNum) -> new GatewayRow(
                        nullableLong(rs, "generation"), nullableLong(rs, "applied_generation"),
                        nullableInt(rs, "supported_format"), rs.getString("agent_version"),
                        instant(rs, "started_at"), nullableInt(rs, "in_flight"),
                        nullableInt(rs, "max_in_flight"), nullableInt(rs, "rejected_entries"),
                        nullableLong(rs, "reload_failures"), rs.getString("last_error"),
                        nullableLong(rs, "bodies_dropped"),
                        nullableLong(rs, "usage_ship_failures"),
                        nullableLong(rs, "spool_write_failures"), instant(rs, "last_contact_at"),
                        nullableInt(rs, "upstream_observation_format"),
                        instant(rs, "last_usage_ship_success_at"),
                        instant(rs, "oldest_unshipped_event_at"),
                        nullableLong(rs, "queued_usage_events"),
                        nullableLong(rs, "queued_usage_bytes"),
                        instant(rs, "usage_queue_observed_at"),
                        nullableLong(rs, "usage_queue_scan_failures")));
        return rows.isEmpty() ? GatewayRow.EMPTY : rows.getFirst();
    }

    private static LlmGatewayReportState gatewayReportState(GatewayRow row, Instant now) {
        if (row.lastContactAt() == null) {
            return LlmGatewayReportState.NOT_REPORTED;
        }
        return row.lastContactAt().isBefore(now.minusSeconds(REPORT_STALE_SECONDS))
                ? LlmGatewayReportState.STALE : LlmGatewayReportState.FRESH;
    }

    private static LlmGatewayStatusResponse gatewayResponse(GatewayRow row,
            LlmGatewayReportState state, boolean diagnostics, Instant now) {
        return new LlmGatewayStatusResponse(state, usageQueueReportState(row, now),
                diagnostics ? row.generation() : null,
                diagnostics ? row.appliedGeneration() : null,
                diagnostics ? row.supportedFormat() : null,
                diagnostics ? row.agentVersion() : null,
                diagnostics ? row.startedAt() : null,
                diagnostics ? row.inFlight() : null,
                diagnostics ? row.maxInFlight() : null,
                diagnostics ? row.rejectedEntries() : null,
                diagnostics ? row.reloadFailures() : null,
                diagnostics ? row.lastError() : null,
                diagnostics ? row.bodiesDropped() : null,
                diagnostics ? row.usageShipFailures() : null,
                diagnostics ? row.spoolWriteFailures() : null,
                row.lastContactAt(),
                diagnostics ? row.lastUsageShipSuccessAt() : null,
                diagnostics ? row.oldestUnshippedEventAt() : null,
                diagnostics ? row.queuedUsageEvents() : null,
                diagnostics ? row.queuedUsageBytes() : null,
                diagnostics ? row.usageQueueObservedAt() : null,
                diagnostics ? row.usageQueueScanFailures() : null);
    }

    private static LlmGatewayReportState usageQueueReportState(GatewayRow row, Instant now) {
        if (row.usageQueueObservedAt() == null) {
            return LlmGatewayReportState.NOT_REPORTED;
        }
        return row.usageQueueObservedAt().isBefore(now.minusSeconds(10 * 60L))
                ? LlmGatewayReportState.STALE : LlmGatewayReportState.FRESH;
    }

    private UpstreamRow upstreamRow(ResultSet rs) throws SQLException {
        String registryRef = rs.getString("registry_ref");
        String stateRef = rs.getString("state_ref");
        return new UpstreamRow(
                rs.getObject("public_id", UUID.class), registryRef,
                enumOrNull(LlmUpstreamKind.class, rs.getString("kind")),
                rs.getString("display_name"), rs.getObject("org_public_id", UUID.class),
                rs.getObject("dedicated", Boolean.class),
                rs.getObject("enabled", Boolean.class),
                rs.getObject("passthrough", Boolean.class), stateRef,
                stateRef != null && rs.getBoolean("configured"), instant(rs, "last_reported_at"),
                instant(rs, "passive_last_attempt_at"),
                instant(rs, "passive_last_success_at"),
                instant(rs, "passive_last_failure_at"),
                rs.getString("passive_last_failure_type"),
                nullableInt(rs, "passive_consecutive_failures"),
                instant(rs, "passive_cooldown_until"),
                instant(rs, "active_last_attempt_at"), instant(rs, "active_last_success_at"),
                instant(rs, "active_last_failure_at"),
                enumOr(LlmActiveProbeStatus.class, rs.getString("active_status"),
                        LlmActiveProbeStatus.UNKNOWN),
                rs.getString("active_failure_type"), nullableLong(rs, "active_latency_ms"),
                nullableInt(rs, "active_probe_interval_seconds"),
                nullableInt(rs, "active_model_count"),
                nullableInt(rs, "active_consecutive_failures"),
                enumOr(LlmCatalogStatus.class, rs.getString("catalog_status"),
                        LlmCatalogStatus.UNKNOWN),
                nullableInt(rs, "catalog_expected_model_count"),
                nullableInt(rs, "catalog_missing_model_count"),
                nullableInt(rs, "catalog_unexpected_model_count"),
                stringList(rs.getString("catalog_missing_public_models")));
    }

    private static LlmUpstreamReportState upstreamReportState(UpstreamRow row,
            GatewayRow gateway, LlmGatewayReportState gatewayState, Instant now) {
        if (row.registryRef() == null) {
            return LlmUpstreamReportState.UNREGISTERED;
        }
        if (gateway.observationFormat() == null || gateway.observationFormat() != 1) {
            return LlmUpstreamReportState.NOT_REPORTED;
        }
        if (row.stateRef() == null) {
            return LlmUpstreamReportState.MISSING;
        }
        if (!row.configured()) {
            return LlmUpstreamReportState.DECONFIGURED;
        }
        if (gatewayState == LlmGatewayReportState.STALE || row.lastReportedAt() == null
                || row.lastReportedAt().isBefore(now.minusSeconds(REPORT_STALE_SECONDS))) {
            return LlmUpstreamReportState.STALE;
        }
        return LlmUpstreamReportState.OK;
    }

    private static LlmUpstreamAvailability availability(UpstreamRow row,
            LlmUpstreamReportState reportState, GatewayRow gateway,
            LlmGatewayReportState gatewayReportState, Instant now) {
        if (gatewayReportState != LlmGatewayReportState.FRESH
                || gateway.observationFormat() == null || gateway.observationFormat() != 1
                || !row.configured() || row.lastReportedAt() == null
                || row.lastReportedAt().isBefore(now.minusSeconds(REPORT_STALE_SECONDS))) {
            return LlmUpstreamAvailability.UNKNOWN;
        }
        if (reportState != LlmUpstreamReportState.OK
                && reportState != LlmUpstreamReportState.UNREGISTERED) {
            return LlmUpstreamAvailability.UNKNOWN;
        }
        LlmUpstreamAvailability result = worse(activeAvailability(row, now),
                passiveAvailability(row, now));
        if (row.catalogStatus() == LlmCatalogStatus.MISMATCH
                && isFresh(row.activeLastSuccessAt(), activeMaxAgeSeconds(row), now)) {
            result = worse(result, LlmUpstreamAvailability.DEGRADED);
        }
        return result;
    }

    private static LlmUpstreamAvailability activeAvailability(UpstreamRow row, Instant now) {
        if (row.activeStatus() == LlmActiveProbeStatus.UNKNOWN
                || row.activeLastAttemptAt() == null) {
            return LlmUpstreamAvailability.UNKNOWN;
        }
        if (!isFresh(row.activeLastAttemptAt(), activeMaxAgeSeconds(row), now)) {
            return LlmUpstreamAvailability.UNKNOWN;
        }
        if (row.activeStatus() == LlmActiveProbeStatus.OK) {
            return LlmUpstreamAvailability.HEALTHY;
        }
        if (row.activeStatus() == LlmActiveProbeStatus.AUTH_UNVERIFIED) {
            return LlmUpstreamAvailability.DEGRADED;
        }
        return NON_OUTAGE_FAILURES.contains(normalized(row.activeFailureType()))
                    ? LlmUpstreamAvailability.HEALTHY
                    : unavailableFailure(row.activeFailureType(), row.activeFailures())
                        ? LlmUpstreamAvailability.UNAVAILABLE
                        : LlmUpstreamAvailability.DEGRADED;
    }

    private static LlmUpstreamAvailability passiveAvailability(UpstreamRow row, Instant now) {
        if (row.passiveLastSuccessAt() == null && row.passiveLastFailureAt() == null) {
            return LlmUpstreamAvailability.UNKNOWN;
        }
        // lastAttemptAt also advances for key-local/cancelled requests whose
        // outcome is intentionally omitted from shared health. It is display
        // metadata, not evidence that can refresh an older success/failure.
        Instant evidenceAt = latest(row.passiveLastSuccessAt(), row.passiveLastFailureAt());
        if (evidenceAt == null || evidenceAt.isBefore(now.minusSeconds(PASSIVE_FRESH_SECONDS))) {
            return LlmUpstreamAvailability.UNKNOWN;
        }
        boolean latestIsFailure = row.passiveLastFailureAt() != null
                && (row.passiveLastSuccessAt() == null
                    || row.passiveLastFailureAt().isAfter(row.passiveLastSuccessAt()));
        if (!latestIsFailure
                || NON_OUTAGE_FAILURES.contains(normalized(row.passiveFailureType()))) {
            return LlmUpstreamAvailability.HEALTHY;
        }
        boolean cooldown = row.passiveCooldownUntil() != null
                && row.passiveCooldownUntil().isAfter(now);
        return cooldown || unavailableFailure(row.passiveFailureType(), row.passiveFailures())
                ? LlmUpstreamAvailability.UNAVAILABLE : LlmUpstreamAvailability.DEGRADED;
    }

    private static @Nullable Instant latest(@Nullable Instant first, @Nullable Instant second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isAfter(second) ? first : second;
    }

    private static long activeMaxAgeSeconds(UpstreamRow row) {
        long interval = row.activeProbeIntervalSeconds() != null
                ? row.activeProbeIntervalSeconds()
                : row.kind() == LlmUpstreamKind.ON_PREM ? 60L : 300L;
        return interval * 3L;
    }

    private static boolean isFresh(@Nullable Instant observedAt, long maxAgeSeconds, Instant now) {
        return observedAt != null && !observedAt.isBefore(now.minusSeconds(maxAgeSeconds));
    }

    private static LlmUpstreamAvailability worse(LlmUpstreamAvailability first,
            LlmUpstreamAvailability second) {
        return severity(first) >= severity(second) ? first : second;
    }

    private static int severity(LlmUpstreamAvailability value) {
        return switch (value) {
            case UNKNOWN -> 0;
            case HEALTHY -> 1;
            case DEGRADED -> 2;
            case UNAVAILABLE -> 3;
        };
    }

    private static boolean unavailableFailure(@Nullable String type, @Nullable Integer failures) {
        return !NON_OUTAGE_FAILURES.contains(normalized(type))
                && failures != null && failures >= 3;
    }

    private static String normalized(@Nullable String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    private static LlmUpstreamStatusResponse toStatus(UpstreamRow row,
            LlmUpstreamReportState reportState, LlmUpstreamAvailability availability,
            boolean sysDiagnostics, Instant now) {
        String actualRef = row.registryRef() == null ? row.stateRef() : row.registryRef();
        String name = row.displayName() == null ? "등록되지 않은 upstream" : row.displayName();
        return new LlmUpstreamStatusResponse(row.publicId(), sysDiagnostics ? actualRef : null,
                name, row.kind(), sysDiagnostics ? row.orgPublicId() : null,
                row.dedicated(), row.enabled(),
                row.configured(), reportState, availability, row.lastReportedAt(),
                new LlmPassiveObservationResponse(row.passiveLastAttemptAt(),
                        row.passiveLastSuccessAt(), row.passiveLastFailureAt(),
                        row.passiveFailureType(), row.passiveFailures(),
                        row.passiveCooldownUntil()),
                new LlmActiveObservationResponse(row.activeLastAttemptAt(),
                        row.activeLastSuccessAt(), row.activeLastFailureAt(), row.activeStatus(),
                        row.activeFailureType(), row.activeProbeIntervalSeconds(),
                        activeStale(row, now), row.activeLatencyMs(), row.activeModelCount(),
                        row.activeFailures()),
                new LlmCatalogObservationResponse(row.catalogStatus(),
                        row.catalogExpectedModelCount(), catalogMissingCount(row),
                        catalogUnexpectedCount(row),
                        sysDiagnostics ? row.catalogMissingPublicModels() : List.of()));
    }

    private static @Nullable Integer catalogMissingCount(UpstreamRow row) {
        if (row.catalogMissingModelCount() != null) {
            return row.catalogMissingModelCount();
        }
        return row.catalogStatus() == LlmCatalogStatus.MATCH
                || row.catalogStatus() == LlmCatalogStatus.MISMATCH ? 0 : null;
    }

    private static boolean activeStale(UpstreamRow row, Instant now) {
        return row.activeStatus() != LlmActiveProbeStatus.UNKNOWN
                && row.activeLastAttemptAt() != null
                && !isFresh(row.activeLastAttemptAt(), activeMaxAgeSeconds(row), now);
    }

    private static @Nullable Integer catalogUnexpectedCount(UpstreamRow row) {
        if (row.catalogUnexpectedModelCount() != null) {
            return row.catalogUnexpectedModelCount();
        }
        boolean compared = (row.catalogStatus() == LlmCatalogStatus.MATCH
                || row.catalogStatus() == LlmCatalogStatus.MISMATCH)
                && Boolean.FALSE.equals(row.passthrough());
        return compared ? 0 : null;
    }

    private static LlmUpstreamMetricResponse upstreamMetric(ResultSet rs,
            boolean sysDiagnostics) throws SQLException {
        long outcomes = rs.getLong("final_outcomes");
        long timeoutOrError = rs.getLong("timeout_or_error");
        long attemptsKnown = rs.getLong("attempts_known");
        long multi = rs.getLong("multi_attempt");
        long attemptsSum = rs.getLong("attempts_sum");
        String ref = rs.getString("upstream_ref");
        String displayName = rs.getString("display_name");
        return new LlmUpstreamMetricResponse(rs.getObject("public_id", UUID.class),
                sysDiagnostics ? ref : null,
                displayName == null ? "등록되지 않은 upstream" : displayName,
                outcomes, rs.getLong("succeeded"), timeoutOrError,
                ratio(timeoutOrError, outcomes), rs.getLong("input_tokens"),
                rs.getLong("output_tokens"), attemptsKnown, multi,
                ratio(multi, attemptsKnown), ratio(attemptsSum, attemptsKnown),
                rs.getLong("latency_samples"), nullableLong(rs, "latency_p50"),
                nullableLong(rs, "latency_p95"), nullableLong(rs, "latency_p99"));
    }

    private static LlmUpstreamMetricResponse redactMetricRef(LlmUpstreamMetricResponse metric) {
        return new LlmUpstreamMetricResponse(metric.id(), null, metric.name(),
                metric.finalOutcomes(), metric.succeeded(), metric.timeoutOrError(),
                metric.timeoutOrErrorRate(), metric.inputTokens(), metric.outputTokens(),
                metric.attemptsKnown(), metric.multiAttemptRequests(), metric.multiAttemptRate(),
                metric.attemptAmplification(), metric.latencySamples(), metric.latencyP50Ms(),
                metric.latencyP95Ms(), metric.latencyP99Ms());
    }

    private static List<Object> timeAndScopeArgs(Instant from, Instant to, OrgScope scope) {
        List<Object> args = new ArrayList<>();
        args.add(OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC));
        args.add(OffsetDateTime.ofInstant(to, java.time.ZoneOffset.UTC));
        if (!scope.isUnrestricted()) {
            args.add(scope.arrayParam());
            args.add(scope.arrayParam());
        }
        return args;
    }

    private List<String> stringList(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(objectMapper.readValue(json, String[].class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return Math.round(((double) numerator / denominator) * 1_000_000.0) / 1_000_000.0;
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static @Nullable Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static @Nullable Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, @Nullable String value, E fallback) {
        E parsed = enumOrNull(type, value);
        return parsed == null ? fallback : parsed;
    }

    private static <E extends Enum<E>> @Nullable E enumOrNull(Class<E> type,
            @Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record MetricSummary(long total, long attributed, long attemptsKnown, long estimated) {
    }

    private record GatewayRow(
            @Nullable Long generation,
            @Nullable Long appliedGeneration,
            @Nullable Integer supportedFormat,
            @Nullable String agentVersion,
            @Nullable Instant startedAt,
            @Nullable Integer inFlight,
            @Nullable Integer maxInFlight,
            @Nullable Integer rejectedEntries,
            @Nullable Long reloadFailures,
            @Nullable String lastError,
            @Nullable Long bodiesDropped,
            @Nullable Long usageShipFailures,
            @Nullable Long spoolWriteFailures,
            @Nullable Instant lastContactAt,
            @Nullable Integer observationFormat,
            @Nullable Instant lastUsageShipSuccessAt,
            @Nullable Instant oldestUnshippedEventAt,
            @Nullable Long queuedUsageEvents,
            @Nullable Long queuedUsageBytes,
            @Nullable Instant usageQueueObservedAt,
            @Nullable Long usageQueueScanFailures) {

        private static final GatewayRow EMPTY = new GatewayRow(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    private record UpstreamRow(
            @Nullable UUID publicId,
            @Nullable String registryRef,
            @Nullable LlmUpstreamKind kind,
            @Nullable String displayName,
            @Nullable UUID orgPublicId,
            @Nullable Boolean dedicated,
            @Nullable Boolean enabled,
            @Nullable Boolean passthrough,
            @Nullable String stateRef,
            boolean configured,
            @Nullable Instant lastReportedAt,
            @Nullable Instant passiveLastAttemptAt,
            @Nullable Instant passiveLastSuccessAt,
            @Nullable Instant passiveLastFailureAt,
            @Nullable String passiveFailureType,
            @Nullable Integer passiveFailures,
            @Nullable Instant passiveCooldownUntil,
            @Nullable Instant activeLastAttemptAt,
            @Nullable Instant activeLastSuccessAt,
            @Nullable Instant activeLastFailureAt,
            LlmActiveProbeStatus activeStatus,
            @Nullable String activeFailureType,
            @Nullable Long activeLatencyMs,
            @Nullable Integer activeProbeIntervalSeconds,
            @Nullable Integer activeModelCount,
            @Nullable Integer activeFailures,
            LlmCatalogStatus catalogStatus,
            @Nullable Integer catalogExpectedModelCount,
            @Nullable Integer catalogMissingModelCount,
            @Nullable Integer catalogUnexpectedModelCount,
            List<String> catalogMissingPublicModels) {
    }
}
