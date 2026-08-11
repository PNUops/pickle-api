package kr.ac.pusan.pickle.llm;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.llm.dto.LlmSyncRequest;
import kr.ac.pusan.pickle.llm.dto.LlmSyncResponse;
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
                   k.public_id, k.token_hash, k.status::text as status, k.expires_at,
                   k.rpm, k.tpm, k.concurrency, k.record_bodies
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

    private final JdbcTemplate jdbcTemplate;
    private final LlmGatewayGenerations llmGatewayGenerations;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public LlmSyncService(JdbcTemplate jdbcTemplate, LlmGatewayGenerations llmGatewayGenerations,
            AuditService auditService, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.llmGatewayGenerations = llmGatewayGenerations;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LlmSyncResponse sync(LlmSyncRequest request) {
        long generation = upsertReport(request);
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
        return jdbcTemplate.queryForObject("""
                insert into llm_gateway_state (id, applied_generation, supported_format,
                    agent_version, started_at, in_flight, max_in_flight, upstream_refs,
                    rejected_entries, reload_failures, last_error, bodies_dropped,
                    usage_ship_failures, spool_write_failures, last_contact_at,
                    contact_lost_since, updated_at)
                values (true, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), null, now())
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
                    last_contact_at = now(), contact_lost_since = null, updated_at = now()
                returning generation
                """, Long.class,
                request.appliedGeneration(), request.supportedFormat(), agentVersion,
                request.startedAt() == null ? null : request.startedAt().atOffset(ZoneOffset.UTC),
                request.inFlight(), request.maxInFlight(), upstreamRefs,
                request.rejectedEntries(), request.reloadFailures(), lastError,
                request.bodiesDropped(), request.usageShipFailures(),
                request.spoolWriteFailures());
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
            String clean = Texts.sanitizeReported(ref, 128);
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
            List<LlmSyncResponse.KeyEntry> keys = new ArrayList<>();
            while (rs.next()) {
                generation = rs.getLong("generation");
                serviceEnabled = rs.getBoolean("service_enabled");
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
                        // Long-window quota accounting is a later round; until
                        // it exists the api has decided nothing, and absent
                        // accounting must not lock keys out.
                        false,
                        rs.getBoolean("record_bodies")));
            }
            // models is an EMPTY ARRAY, not an omission: "no models" is this
            // deployment's real state until the catalogue lands with the DGX
            // round, and the gateway must apply it (omission would mean
            // "unchanged"). Serving it alongside keys keeps the
            // both-or-neither rule intact.
            return new LlmSyncResponse.Document(DOCUMENT_FORMAT, generation, serviceEnabled,
                    List.of(), List.copyOf(keys));
        });
    }

    private static LlmSyncResponse.KeyLimits limits(Integer rpm, Integer tpm,
            Integer concurrency) {
        if (rpm == null && tpm == null && concurrency == null) {
            return null; // no limits of its own: the member drops out entirely
        }
        return new LlmSyncResponse.KeyLimits(rpm, tpm, concurrency);
    }
}
