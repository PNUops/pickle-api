package kr.ac.pusan.pickle.llm;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.crypto.CredentialCipher;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.llm.dto.LlmSyncRequest;
import kr.ac.pusan.pickle.llm.dto.LlmSyncResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The LLM gateway sync heartbeat: stores the gateway's self-report, stamps
 * contact, and answers either the bare generation or the full authorization
 * document.
 *
 * <p><b>The unchanged path never builds the document.</b> The report upsert
 * returns the current generation, and only when the reported
 * {@code appliedGeneration} differs does the document query run — 12 polls a
 * minute answered by what is effectively one small upsert. (The relay sync
 * service is deliberately NOT the template here: it runs its full snapshot
 * join on every poll and throws the rows away when the generation matches.)</p>
 *
 * <p><b>The document is read by one SQL statement</b> (generation +
 * service-enabled + key rows in a single MVCC view). This tree never raises
 * the isolation level, so reading them separately under READ COMMITTED could
 * pair a new generation with an older row set — which the gateway would then
 * confirm as applied, and the missed change would never be re-sent.</p>
 *
 * <p>A reported generation <b>above</b> ours is not treated as a violation:
 * it means this side went backwards (a restored backup) while the gateway's
 * persisted high-water mark did not. Refusing to act would wedge the link
 * permanently — every document we could produce would sit below the gateway's
 * floor — so the counter is raised above the reported value, the full
 * document served, and the event audited once. (The relay link audits and
 * discards here; copying that would wedge this link.)</p>
 */
@Service
public class LlmSyncService {

    /** Server-side cap on any gateway-reported string persisted anywhere. */
    static final int REPORTED_TEXT_MAX = 1024;

    /** The one document format this build can produce. */
    static final int DOCUMENT_FORMAT = 1;

    /** Upstream names stored from the report; anything past this is dropped. */
    static final int MAX_UPSTREAM_REFS = 32;

    /** Missing catalogue names are diagnostic samples, never an unbounded dump. */
    static final int MAX_MISSING_PUBLIC_MODELS = 20;

    private static final Logger log = LoggerFactory.getLogger(LlmSyncService.class);

    /**
     * Generation + kill switch + key rows in ONE statement (single MVCC
     * view). The join condition — not a where clause, so a state with zero
     * keys still yields its generation row — implements the retention the
     * gateway relies on:
     *
     * <ul>
     *   <li>A key without a minted secret (PENDING, or any row with no
     *       {@code token_hash}) is ABSENT from the document, not
     *       present-and-refused: it authenticates nothing, so there is no
     *       state to publish about it, and a null hash would be a document
     *       the gateway rejects outright. The null-hash condition is the real
     *       gate (a revoked-before-mint key is not PENDING but still has no
     *       secret); the status condition is the belt.</li>
     *   <li>Revoked and expired keys STAY in the document for a 30-day grace
     *       period, status included, so the gateway can answer
     *       "api_key_revoked" instead of "invalid_api_key"; past the grace
     *       they are dropped and "invalid_api_key" becomes the correct
     *       answer. The payload stays bounded that way.</li>
     *   <li>An EXPIRED row must carry the expiry the gateway enforces; one
     *       without it (inconsistent data) is left out rather than served in
     *       a shape that could fail open.</li>
     * </ul>
     */
    private static final String DOCUMENT_SQL = """
            select s.generation, s.service_enabled,
                   (select coalesce(json_agg(json_build_object(
                              'publicName', m.public_name,
                              'upstreamRef', m.upstream_ref,
                              'upstreamModel', m.upstream_model,
                              'fallbackRef', m.fallback_ref,
                              'visibility', m.visibility,
                              'budgetAxis', m.budget_axis,
                              'maxInputTokens', m.max_input_tokens,
                              'maxOutputTokens', m.max_output_tokens)), '[]'::json)
                      from llm_models m where m.enabled) as models,
                   (select u.ref from llm_upstreams u
                     where u.passthrough and u.enabled) as passthrough_ref,
                   k.public_id, k.token_hash, k.status::text as status, k.expires_at,
                   k.rpm, k.tpm, k.concurrency, k.quota_exhausted, k.record_bodies,
                   k.credit_limit, k.openrouter_key_enc,
                   -- Same clock as the row filters below, so "still live" means
                   -- one thing across this statement. Read by creditPending().
                   (k.expires_at is null or k.expires_at > now()) as not_expired
              from llm_gateway_state s
              left join llm_api_keys k
                on k.token_hash is not null
               and k.status <> 'PENDING'
               and (k.status <> 'REVOKED' or k.revoked_at is null
                    or k.revoked_at > now() - interval '30 days')
               and (k.expires_at is null or k.expires_at > now() - interval '30 days')
               and not (k.status = 'EXPIRED' and k.expires_at is null)
             where s.id
            """;

    /**
     * The upstream ref the per-key OpenRouter credential is keyed by in the
     * document. Matches the {@code llm_upstreams} seed row and the gateway's
     * env block name; lowercase, as the gateway normalizes refs.
     */
    static final String OPENROUTER_REF = "openrouter";

    private final JdbcTemplate jdbcTemplate;
    private final LlmGatewayGenerations llmGatewayGenerations;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final CredentialCipher credentialCipher;

    public LlmSyncService(JdbcTemplate jdbcTemplate, LlmGatewayGenerations llmGatewayGenerations,
            AuditService auditService, ObjectMapper objectMapper,
            CredentialCipher credentialCipher) {
        this.jdbcTemplate = jdbcTemplate;
        this.llmGatewayGenerations = llmGatewayGenerations;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.credentialCipher = credentialCipher;
    }

    @Transactional
    public LlmSyncResponse sync(LlmSyncRequest request) {
        long generation = upsertReport(request);
        ingestUpstreamObservations(request);
        long reported = request.appliedGeneration();

        boolean forceFull = false;
        if (reported > generation) {
            long raisedTo = llmGatewayGenerations.raiseAbove(reported);
            // Direct record (not after-commit): a data-recovery signal worth
            // keeping even if something later in this tx were to fail.
            auditService.record(null, AuditService.ACTOR_ROLE_LLM_GATEWAY,
                    AuditService.LLM_GATEWAY_GENERATION_RAISE, "llm_gateway", null,
                    Map.of("reportedGeneration", reported, "previousGeneration", generation,
                            "raisedTo", raisedTo), null);
            log.warn("LLM gateway reported generation {} above ours ({}) — counter restored "
                    + "backwards? raised to {} and serving the full document",
                    reported, generation, raisedTo);
            generation = raisedTo;
            forceFull = true;
        }
        if (!forceFull && reported == generation) {
            return new LlmSyncResponse.Unchanged(generation);
        }
        if (request.supportedFormat() < DOCUMENT_FORMAT) {
            // Nothing below format 1 exists to serve; the gateway will refuse
            // and report it via rejectedEntries/lastError, which is the only
            // visible outcome possible here.
            log.warn("LLM gateway supports document format {} below the minimum {}",
                    request.supportedFormat(), DOCUMENT_FORMAT);
        }
        return readDocument();
    }

    /**
     * Stores the self-report and stamps contact in one upsert — the sync IS
     * the liveness signal, so contact-lost clears here — and returns the
     * current generation without touching the key table. Creates the state
     * row on the very first poll (no migration seeds it).
     */
    private long upsertReport(LlmSyncRequest request) {
        String agentVersion = Texts.sanitizeReported(request.agentVersion(), REPORTED_TEXT_MAX);
        String lastError = Texts.sanitizeReported(request.lastError(), REPORTED_TEXT_MAX);
        String upstreamRefs = upstreamRefsJson(request.upstreamRefs());
        OffsetDateTime queueObservedAt = offset(request.usageQueueObservedAt());
        Long queuedUsageEvents = queueObservedAt == null ? null
                : defaultZero(request.queuedUsageEvents());
        Long queuedUsageBytes = queueObservedAt == null ? null
                : defaultZero(request.queuedUsageBytes());
        Long usageQueueScanFailures = queueObservedAt == null ? null
                : defaultZero(request.usageQueueScanFailures());
        boolean currentReporter = Integer.valueOf(1).equals(request.upstreamObservationFormat());
        return jdbcTemplate.queryForObject("""
                insert into llm_gateway_state (id, applied_generation, supported_format,
                    agent_version, started_at, in_flight, max_in_flight, upstream_refs,
                    rejected_entries, reload_failures, last_error, bodies_dropped,
                    usage_ship_failures, spool_write_failures, upstream_observation_format,
                    last_usage_ship_success_at, oldest_unshipped_event_at,
                    queued_usage_events, queued_usage_bytes, usage_queue_observed_at,
                    usage_queue_scan_failures, last_contact_at,
                    contact_lost_since, updated_at)
                values (true, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    now(), null, now())
                on conflict (id) do update set
                    applied_generation = excluded.applied_generation,
                    supported_format = excluded.supported_format,
                    agent_version = excluded.agent_version,
                    started_at = excluded.started_at,
                    in_flight = excluded.in_flight,
                    max_in_flight = excluded.max_in_flight,
                    upstream_refs = excluded.upstream_refs,
                    rejected_entries = excluded.rejected_entries,
                    reload_failures = excluded.reload_failures,
                    last_error = excluded.last_error,
                    bodies_dropped = excluded.bodies_dropped,
                    usage_ship_failures = excluded.usage_ship_failures,
                    spool_write_failures = excluded.spool_write_failures,
                    upstream_observation_format = excluded.upstream_observation_format,
                    last_usage_ship_success_at = excluded.last_usage_ship_success_at,
                    oldest_unshipped_event_at = excluded.oldest_unshipped_event_at,
                    queued_usage_events = excluded.queued_usage_events,
                    queued_usage_bytes = excluded.queued_usage_bytes,
                    usage_queue_observed_at = excluded.usage_queue_observed_at,
                    usage_queue_scan_failures = excluded.usage_queue_scan_failures,
                    last_contact_at = now(), contact_lost_since = null, updated_at = now()
                returning generation
                """, Long.class,
                request.appliedGeneration(), request.supportedFormat(), agentVersion,
                request.startedAt() == null ? null : request.startedAt().atOffset(ZoneOffset.UTC),
                request.inFlight(), request.maxInFlight(), upstreamRefs,
                currentReporterCounter(request.rejectedEntries(), currentReporter),
                currentReporterCounter(request.reloadFailures(), currentReporter), lastError,
                currentReporterCounter(request.bodiesDropped(), currentReporter),
                currentReporterCounter(request.usageShipFailures(), currentReporter),
                currentReporterCounter(request.spoolWriteFailures(), currentReporter),
                nonNegative(request.upstreamObservationFormat()),
                offset(request.lastUsageShipSuccessAt()), offset(request.oldestUnshippedEventAt()),
                queuedUsageEvents, queuedUsageBytes, queueObservedAt,
                usageQueueScanFailures);
    }

    /**
     * Format 1 makes {@code upstreams} an authoritative list of what this
     * gateway currently has configured. A gateway that predates the format
     * omits the version; that omission updates no upstream row and therefore
     * cannot turn a rollback into a mass deconfiguration.
     */
    private void ingestUpstreamObservations(LlmSyncRequest request) {
        if (request.upstreamObservationFormat() == null
                || request.upstreamObservationFormat() != 1) {
            return;
        }
        if (request.upstreams() == null) {
            log.warn("LLM gateway observation format 1 omitted upstreams; preserving existing "
                    + "configured flags");
            return;
        }
        Set<String> reportedRefs = new LinkedHashSet<>();
        List<LlmSyncRequest.UpstreamObservation> observations = request.upstreams();
        int invalid = 0;
        int duplicates = 0;
        int overflow = Math.max(0, observations.size() - MAX_UPSTREAM_REFS);
        for (LlmSyncRequest.UpstreamObservation observation : observations) {
            if (observation == null) {
                invalid++;
                continue;
            }
            String ref = normalizedRef(observation.ref());
            if (ref == null) {
                invalid++;
                continue;
            }
            if (!reportedRefs.add(ref)) {
                duplicates++;
                continue;
            }
            if (reportedRefs.size() <= MAX_UPSTREAM_REFS) {
                upsertUpstreamObservation(ref, observation);
            }
        }
        boolean complete = invalid == 0 && duplicates == 0 && overflow == 0;
        if (!complete) {
            // Do not log any reported ref: even a malformed one is gateway
            // configuration data. Counts are enough to diagnose the shape.
            log.warn("LLM gateway upstream observation list incomplete (invalid={}, "
                    + "duplicates={}, overflow={}); valid rows updated, existing configured "
                    + "flags preserved", invalid, duplicates, overflow);
            return;
        }
        if (reportedRefs.isEmpty()) {
            jdbcTemplate.update("""
                    update llm_upstream_state
                       set configured = false,
                           deconfigured_at = coalesce(deconfigured_at, now())
                     where configured
                    """);
            return;
        }
        String placeholders = String.join(", ",
                java.util.Collections.nCopies(reportedRefs.size(), "?"));
        String sql = """
                update llm_upstream_state
                   set configured = false,
                       deconfigured_at = coalesce(deconfigured_at, now())
                 where configured and ref not in (%s)
                """.formatted(placeholders);
        jdbcTemplate.update(sql, reportedRefs.toArray());
    }

    private void upsertUpstreamObservation(String ref,
            LlmSyncRequest.UpstreamObservation observation) {
        LlmSyncRequest.PassiveObservation passive = observation.passive();
        LlmSyncRequest.ActiveObservation active = observation.active();
        LlmSyncRequest.CatalogObservation catalog = observation.catalog();
        jdbcTemplate.update("""
                insert into llm_upstream_state (
                    ref, configured, last_reported_at, deconfigured_at,
                    passive_last_attempt_at, passive_last_success_at,
                    passive_last_failure_at, passive_last_failure_type,
                    passive_consecutive_failures, passive_cooldown_until,
                    active_last_attempt_at, active_last_success_at, active_last_failure_at,
                    active_status, active_failure_type, active_probe_interval_seconds,
                    active_latency_ms,
                    active_model_count, active_consecutive_failures,
                    catalog_status, catalog_expected_model_count,
                    catalog_missing_model_count, catalog_unexpected_model_count,
                    catalog_missing_public_models)
                values (?, true, now(), null, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (ref) do update set
                    configured = true, last_reported_at = now(), deconfigured_at = null,
                    passive_last_attempt_at = excluded.passive_last_attempt_at,
                    passive_last_success_at = excluded.passive_last_success_at,
                    passive_last_failure_at = excluded.passive_last_failure_at,
                    passive_last_failure_type = excluded.passive_last_failure_type,
                    passive_consecutive_failures = excluded.passive_consecutive_failures,
                    passive_cooldown_until = excluded.passive_cooldown_until,
                    active_last_attempt_at = excluded.active_last_attempt_at,
                    active_last_success_at = excluded.active_last_success_at,
                    active_last_failure_at = excluded.active_last_failure_at,
                    active_status = excluded.active_status,
                    active_failure_type = excluded.active_failure_type,
                    active_probe_interval_seconds = excluded.active_probe_interval_seconds,
                    active_latency_ms = excluded.active_latency_ms,
                    active_model_count = excluded.active_model_count,
                    active_consecutive_failures = excluded.active_consecutive_failures,
                    catalog_status = excluded.catalog_status,
                    catalog_expected_model_count = excluded.catalog_expected_model_count,
                    catalog_missing_model_count = excluded.catalog_missing_model_count,
                    catalog_unexpected_model_count = excluded.catalog_unexpected_model_count,
                    catalog_missing_public_models = excluded.catalog_missing_public_models
                """, ref,
                offset(passive == null ? null : passive.lastAttemptAt()),
                offset(passive == null ? null : passive.lastSuccessAt()),
                offset(passive == null ? null : passive.lastFailureAt()),
                reportedText(passive == null ? null : passive.lastFailureType(), 128),
                nonNegative(passive == null ? null : passive.consecutiveFailures()),
                offset(passive == null ? null : passive.cooldownUntil()),
                offset(active == null ? null : active.lastAttemptAt()),
                offset(active == null ? null : active.lastSuccessAt()),
                offset(active == null ? null : active.lastFailureAt()),
                activeStatus(active == null ? null : active.status()),
                reportedText(active == null ? null : active.lastFailureType(), 128),
                positive(active == null ? null : active.intervalSeconds()),
                nonNegative(active == null ? null : active.latencyMs()),
                activeModelCount(active),
                nonNegative(active == null ? null : active.consecutiveFailures()),
                catalogStatus(catalog == null ? null : catalog.status()),
                nonNegative(catalog == null ? null : catalog.expectedModelCount()),
                nonNegative(catalog == null ? null : catalog.missingModelCount()),
                nonNegative(catalog == null ? null : catalog.unexpectedModelCount()),
                missingPublicModelsJson(catalog == null ? null : catalog.missingPublicModels()));
    }

    private String missingPublicModelsJson(List<String> reported) {
        if (reported == null || reported.isEmpty()) {
            return null;
        }
        List<String> sanitized = new ArrayList<>();
        for (String name : reported) {
            if (sanitized.size() >= MAX_MISSING_PUBLIC_MODELS) {
                break;
            }
            String clean = reportedText(name, 256);
            if (clean != null) {
                sanitized.add(clean);
            }
        }
        return sanitized.isEmpty() ? null : objectMapper.writeValueAsString(sanitized);
    }

    private static @Nullable String normalizedRef(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String clean = value.strip();
        if (clean.isEmpty() || clean.length() > 128) {
            return null;
        }
        for (int i = 0; i < clean.length(); i++) {
            if (Character.isISOControl(clean.charAt(i))) {
                return null;
            }
        }
        clean = clean.toLowerCase(Locale.ROOT);
        return clean.matches("[a-z0-9][a-z0-9_-]{0,127}") ? clean : null;
    }

    private static @Nullable String reportedText(@Nullable String value, int max) {
        return Texts.sanitizeReported(value, max);
    }

    private static String activeStatus(@Nullable String value) {
        return switch (value == null ? "" : value.strip().toUpperCase(Locale.ROOT)) {
            case "OK", "AUTH_UNVERIFIED", "FAILED", "UNKNOWN" ->
                    value.strip().toUpperCase(Locale.ROOT);
            default -> "UNKNOWN";
        };
    }

    private static String catalogStatus(@Nullable String value) {
        return switch (value == null ? "" : value.strip().toUpperCase(Locale.ROOT)) {
            case "MATCH", "MISMATCH", "NOT_APPLICABLE", "UNKNOWN" ->
                    value.strip().toUpperCase(Locale.ROOT);
            default -> "UNKNOWN";
        };
    }

    private static @Nullable OffsetDateTime offset(@Nullable Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static @Nullable Integer nonNegative(@Nullable Integer value) {
        return value == null ? null : Math.max(0, value);
    }

    private static @Nullable Integer positive(@Nullable Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    private static @Nullable Long nonNegative(@Nullable Long value) {
        return value == null ? null : Math.max(0L, value);
    }

    private static long defaultZero(@Nullable Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private static @Nullable Integer currentReporterCounter(@Nullable Integer value,
            boolean currentReporter) {
        if (value == null) {
            return currentReporter ? Integer.valueOf(0) : null;
        }
        return nonNegative(value);
    }

    private static @Nullable Long currentReporterCounter(@Nullable Long value,
            boolean currentReporter) {
        if (value == null) {
            return currentReporter ? Long.valueOf(0L) : null;
        }
        return nonNegative(value);
    }

    private static @Nullable Integer activeModelCount(
            LlmSyncRequest.ActiveObservation active) {
        if (active == null) {
            return null;
        }
        Integer count = nonNegative(active.modelCount());
        return count == null && "OK".equals(activeStatus(active.status())) ? 0 : count;
    }

    /**
     * The reported upstream names, sanitized and stored as a JSON array. Kept
     * so the model-save flow (DGX round) can validate a model's
     * {@code upstreamRef}/{@code fallbackRef} against what the caller actually
     * has configured — case-insensitively, as the gateway matches — instead of
     * letting a typo cost the model entry at load time.
     */
    private String upstreamRefsJson(List<String> reported) {
        if (reported == null || reported.isEmpty()) {
            return null;
        }
        List<String> sanitized = new ArrayList<>();
        for (String ref : reported) {
            if (sanitized.size() >= MAX_UPSTREAM_REFS) {
                break;
            }
            String clean = normalizedRef(ref);
            if (clean != null) {
                sanitized.add(clean);
            }
        }
        return sanitized.isEmpty() ? null : objectMapper.writeValueAsString(sanitized);
    }

    // ── document ─────────────────────────────────────────────────────────────

    private LlmSyncResponse readDocument() {
        return jdbcTemplate.query(DOCUMENT_SQL, rs -> {
            long generation = 0;
            boolean serviceEnabled = true;
            String passthroughRef = null;
            List<LlmSyncResponse.ModelEntry> models = List.of();
            List<LlmSyncResponse.KeyEntry> keys = new ArrayList<>();
            while (rs.next()) {
                generation = rs.getLong("generation");
                serviceEnabled = rs.getBoolean("service_enabled");
                passthroughRef = rs.getString("passthrough_ref");
                // Aggregated in the same statement rather than read separately:
                // the whole point of one statement is that the generation and
                // everything the document says cannot come from two different
                // moments. Identical on every row of the key join, so it is
                // parsed once.
                if (models.isEmpty()) {
                    models = parseModels(rs.getString("models"));
                }
                UUID publicId = rs.getObject("public_id", UUID.class);
                if (publicId == null) {
                    continue; // left-join row of a state with no servable key
                }
                String status = rs.getString("status");
                OffsetDateTime expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
                keys.add(new LlmSyncResponse.KeyEntry(
                        publicId.toString(),
                        rs.getString("token_hash"),
                        // The gateway's vocabulary has no EXPIRED: it enforces
                        // expiresAt itself (a key expires between polls, with
                        // no write to bump the generation), so an api-side
                        // EXPIRED row is served ACTIVE with its real expiry
                        // and the gateway keeps saying "expired", not
                        // "revoked". Rows lacking that expiry never reach
                        // here (see DOCUMENT_SQL).
                        "EXPIRED".equals(status) ? "ACTIVE" : status,
                        expiresAt == null ? null : expiresAt.toInstant(),
                        // No RESTRICTED model exists yet; empty reaches PUBLIC
                        // models only, which is the fail-closed default.
                        List.of(),
                        limits(rs.getObject("rpm", Integer.class),
                                rs.getObject("tpm", Integer.class),
                                rs.getObject("concurrency", Integer.class)),
                        // The api's decision, not the gateway's: a day's
                        // running total needs durable state the gateway was
                        // built not to have. Kept as a column so that flipping
                        // it is a write, and a write is what moves the
                        // generation the gateway polls on.
                        rs.getBoolean("quota_exhausted"),
                        rs.getBoolean("record_bodies"),
                        creditPending(status, rs.getBoolean("not_expired"),
                                rs.getBigDecimal("credit_limit"),
                                rs.getString("openrouter_key_enc")),
                        credentialsFor(publicId.toString(), status,
                                rs.getBigDecimal("credit_limit"),
                                rs.getString("openrouter_key_enc"))));
            }
            // models is an EMPTY ARRAY, not an omission: "no models" is this
            // deployment's real state until the catalogue lands with the DGX
            // round, and the gateway must apply it (omission would mean
            // "unchanged"). Serving it alongside keys keeps the
            // both-or-neither rule intact.
            return new LlmSyncResponse.Document(DOCUMENT_FORMAT, generation, serviceEnabled,
                    passthroughRef, models, List.copyOf(keys));
        });
    }

    /**
     * The per-key upstream credential map, or null (the member drops out and
     * the commercial axis is closed for the key). Included for an ACTIVE row
     * with a positive limit and a provisioned secret. Note what the status
     * check does NOT do: a key expired by timestamp still has status ACTIVE
     * (nothing flips the column), so its credential keeps being served
     * through the grace window — safe because the gateway refuses expired
     * keys before any upstream call, and bounded because the OpenRouter key
     * is created with the same expiry and dies on its own clock.
     *
     * <p>A ciphertext that will not decrypt costs that key its credential, not
     * the whole document: revocations must keep flowing even when one row is
     * corrupt, and the loss is visible on the key's own commercial axis.</p>
     */
    /**
     * Whether this key's missing credential is a wait rather than an answer:
     * the money budget is granted and the OpenRouter key has not been created
     * yet. The provisioner attempts it the moment the budget lands and the
     * sweep retries after that, so this state ends on its own.
     *
     * <p>Deliberately narrower than "{@link #credentialsFor} returned null".
     * That happens for four reasons and only this one heals: a key that is
     * not ACTIVE is not waiting on provisioning, a zero budget was never
     * granted one, and a ciphertext that will not decrypt is a fault an
     * operator has to repair. Telling a caller to wait for any of those
     * would be a promise nothing keeps.
     *
     * <p>{@code notExpired} is what keeps that true rather than nearly true.
     * A key expired by timestamp can still carry status ACTIVE in the column
     * and still reach the document (rows stay for 30 days past expiry), and
     * the provisioning sweep skips it — so without it the flag would promise
     * a wait that never ends. The gateway happens to settle expiry during
     * authentication, before any budget check could read the flag, but that
     * is an ordering downstream of here and not something this should lean
     * on. The condition mirrors the sweep's worklist instead, which is the
     * only thing that makes the flag's promise good. It comes from the query
     * rather than a Java clock so that every "still live" in this statement
     * is decided by one clock.
     */
    private static boolean creditPending(String status, boolean notExpired,
            @Nullable BigDecimal creditLimit, @Nullable String keyEnc) {
        return "ACTIVE".equals(status) && notExpired && keyEnc == null
                && creditLimit != null && creditLimit.signum() > 0;
    }

    private @Nullable Map<String, String> credentialsFor(String keyId, String status,
            @Nullable BigDecimal creditLimit, @Nullable String keyEnc) {
        if (!"ACTIVE".equals(status) || keyEnc == null
                || creditLimit == null || creditLimit.signum() <= 0) {
            return null;
        }
        try {
            return Map.of(OPENROUTER_REF, credentialCipher.decrypt(keyEnc));
        } catch (RuntimeException e) {
            log.error("stored OpenRouter credential for key {} failed to decrypt; "
                    + "serving the key without it", keyId, e);
            return null;
        }
    }

    /**
     * The aggregated model rows. An empty array is a real state the gateway
     * applies ("this deployment serves nothing"), distinct from the member
     * being absent, which means unchanged — so a parse failure must not
     * silently become one: it throws, the poll fails, and the gateway keeps
     * its last good document rather than being told everything is gone.
     */
    private List<LlmSyncResponse.ModelEntry> parseModels(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(objectMapper.readValue(json, LlmSyncResponse.ModelEntry[].class));
        } catch (Exception e) {
            throw new IllegalStateException("unreadable model catalogue", e);
        }
    }

    private static LlmSyncResponse.KeyLimits limits(Integer rpm, Integer tpm,
            Integer concurrency) {
        if (rpm == null && tpm == null && concurrency == null) {
            return null; // no limits of its own: the member drops out entirely
        }
        return new LlmSyncResponse.KeyLimits(rpm, tpm, concurrency);
    }
}
