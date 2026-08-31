package kr.ac.pusan.pickle.llm;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.llm.dto.LlmUsageRequest;
import kr.ac.pusan.pickle.llm.dto.LlmUsageResponse;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCreditRefreshScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Usage-event ingest. Delivery is at-least-once from a persisted checkpoint,
 * so the design constraints are:
 *
 * <ul>
 *   <li><b>Dedup on the event id</b> with {@code on conflict do nothing
 *       returning id} read as a nullable — never {@code do update}, the same
 *       id always carries the same content. The nullable read is how accepted
 *       is counted against duplicate (the IP allocator's idiom).</li>
 *   <li><b>A problem with individual events is never a 4xx.</b> The gateway
 *       reads 400/409/413/422 as "this batch is the problem", skips it and
 *       moves its checkpoint past it — those events are gone. Bad events are
 *       counted into {@code rejected} and the batch answers 2xx.</li>
 *   <li><b>Events do not arrive in time order</b> (the checkpoint is per day
 *       file; a request straddling UTC midnight ships late). Everything
 *       time-derived orders by {@code requested_at}, and the last-used stamp
 *       only ever moves forward.</li>
 * </ul>
 */
@Service
public class LlmUsageService {

    /** The contract bound on the opaque event id. */
    static final int MAX_EVENT_ID_LENGTH = 64;

    /** Cap on any reported free-text field persisted here. */
    static final int REPORTED_TEXT_MAX = 256;

    private static final Logger log = LoggerFactory.getLogger(LlmUsageService.class);

    private final JdbcTemplate jdbcTemplate;
    private final LlmQuotaService quotaService;
    private final OpenRouterCreditRefreshScheduler creditRefreshScheduler;

    public LlmUsageService(JdbcTemplate jdbcTemplate, LlmQuotaService quotaService,
            OpenRouterCreditRefreshScheduler creditRefreshScheduler) {
        this.jdbcTemplate = jdbcTemplate;
        this.quotaService = quotaService;
        this.creditRefreshScheduler = creditRefreshScheduler;
    }

    @Transactional
    public LlmUsageResponse ingest(LlmUsageRequest request) {
        List<LlmUsageRequest.UsageEvent> events =
                request.events() == null ? List.of() : request.events();
        Map<String, Long> keyIds = resolveKeyIds(events);
        Map<Long, Instant> lastUsed = new HashMap<>();
        Set<Long> creditExhaustedKeys = new LinkedHashSet<>();

        int accepted = 0;
        int duplicates = 0;
        int rejected = 0;
        for (LlmUsageRequest.UsageEvent event : events) {
            if (event == null) {
                rejected++;
                continue;
            }
            String eventId = event.eventUuid();
            String status = Texts.sanitizeReported(event.status(), REPORTED_TEXT_MAX);
            String errorType = Texts.sanitizeReported(event.errorType(), REPORTED_TEXT_MAX);
            Instant requestedAt = parseInstant(event.requestedAt());
            if (eventId == null || eventId.isBlank() || eventId.length() > MAX_EVENT_ID_LENGTH
                    || containsControlChars(eventId) || status == null || requestedAt == null) {
                rejected++;
                continue;
            }
            // Unattributed events (no keyId, or one that resolves to nothing)
            // are kept with a null key: they are the only trace of a client
            // looping on a bad key.
            Long keyId = event.keyId() == null ? null : keyIds.get(event.keyId().strip());
            Long insertedId = tryInsert(event, eventId, status, errorType, requestedAt, keyId);
            if (insertedId == null) {
                duplicates++;
                continue;
            }
            accepted++;
            if (keyId != null) {
                lastUsed.merge(keyId, requestedAt, (a, b) -> a.isAfter(b) ? a : b);
                if ("credit_exhausted".equals(errorType)) {
                    creditExhaustedKeys.add(keyId);
                }
            }
        }
        // In this transaction, so the events and the quota state they imply
        // become visible together: a poll must never see the tokens without
        // the refusal they earned. The periodic sweep is what releases a key
        // again — this call only ever locks one, because nothing here reduces
        // a day's total.
        //
        // Before stampLastUsed, and that order is load-bearing. Every other
        // writer in this tree takes the generation counter first and key rows
        // second; stamping first would make ingest the one path that goes
        // key rows → counter → key rows, and a revoke or a sweep holding the
        // counter while waiting on the same key row deadlocks against it.
        // Both orderings are correct in isolation; being the odd one out is
        // what costs a transaction.
        quotaService.refresh();
        stampLastUsed(lastUsed);
        requestCreditRefreshes(creditExhaustedKeys);
        if (rejected > 0) {
            log.warn("LLM usage batch: accepted {}, duplicates {}, rejected {}",
                    accepted, duplicates, rejected);
        } else {
            log.info("LLM usage batch: accepted {}, duplicates {}", accepted, duplicates);
        }
        return new LlmUsageResponse(accepted, duplicates, rejected);
    }

    /** The dedup insert; null means the event id already exists (duplicate). */
    private Long tryInsert(LlmUsageRequest.UsageEvent event, String eventId, String status,
            String errorType, Instant requestedAt, Long keyId) {
        return jdbcTemplate.query("""
                insert into llm_usage_events (event_id, key_id, generation, public_model_name,
                    budget_axis, upstream_ref, attempts, status, error_type, input_tokens,
                    output_tokens, estimated, latency_ms, ttft_ms, requested_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (event_id) do nothing
                returning id
                """, rs -> rs.next() ? rs.getLong(1) : null,
                eventId, keyId, event.generation(),
                Texts.sanitizeReported(event.publicModelName(), REPORTED_TEXT_MAX),
                budgetAxis(event.budgetAxis()),
                Texts.sanitizeReported(event.upstreamRef(), REPORTED_TEXT_MAX),
                event.attempts(), status,
                errorType,
                nonNegative(event.inputTokens()), nonNegative(event.outputTokens()),
                Boolean.TRUE.equals(event.estimated()),
                nonNegative(event.latencyMs()), event.ttftMs(),
                requestedAt.atOffset(ZoneOffset.UTC));
    }

    /** Unknown or malformed additive reports remain usable and aggregate as UNKNOWN. */
    private static String budgetAxis(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return "TOKEN".equals(stripped) || "CREDIT".equals(stripped) ? stripped : null;
    }

    /**
     * One batched lookup from the reported (opaque) key ids to internal key
     * rows. Keys live on soft delete precisely so this join keeps working for
     * revoked keys' historical usage.
     */
    private Map<String, Long> resolveKeyIds(List<LlmUsageRequest.UsageEvent> events) {
        Map<String, UUID> parsed = new HashMap<>();
        Set<UUID> wanted = new LinkedHashSet<>();
        for (LlmUsageRequest.UsageEvent event : events) {
            if (event == null || event.keyId() == null || event.keyId().isBlank()) {
                continue;
            }
            String reported = event.keyId().strip();
            if (parsed.containsKey(reported)) {
                continue;
            }
            try {
                UUID publicId = UUID.fromString(reported);
                parsed.put(reported, publicId);
                wanted.add(publicId);
            } catch (IllegalArgumentException e) {
                // Not an id this side ever issued; the event stays, unattributed.
            }
        }
        if (wanted.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(wanted.size(), "?"));
        Map<UUID, Long> byPublicId = new HashMap<>();
        jdbcTemplate.query(
                "select id, public_id from llm_api_keys where public_id in (" + placeholders + ")",
                rs -> {
                    byPublicId.put(rs.getObject("public_id", UUID.class), rs.getLong("id"));
                }, wanted.toArray());
        Map<String, Long> resolved = new HashMap<>();
        for (Map.Entry<String, UUID> entry : parsed.entrySet()) {
            Long id = byPublicId.get(entry.getValue());
            if (id != null) {
                resolved.put(entry.getKey(), id);
            }
        }
        return resolved;
    }

    /**
     * Moves each key's last-used stamp forward to the newest accepted
     * {@code requestedAt} — forward only, because batches arrive out of time
     * order and a late batch must not walk the stamp backwards.
     */
    private void stampLastUsed(Map<Long, Instant> lastUsed) {
        for (Map.Entry<Long, Instant> entry : lastUsed.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            OffsetDateTime at = entry.getValue().atOffset(ZoneOffset.UTC);
            jdbcTemplate.update("""
                    update llm_api_keys
                       set last_used_at = ?
                     where id = ? and (last_used_at is null or last_used_at < ?)
                    """, at, entry.getKey(), at);
        }
    }

    private void requestCreditRefreshes(Set<Long> keyIds) {
        if (keyIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(", ",
                java.util.Collections.nCopies(keyIds.size(), "?"));
        jdbcTemplate.query("""
                select distinct a.public_id
                  from llm_api_keys k
                  join openrouter_accounts a on a.id = k.openrouter_account_id
                 where k.id in (%s)
                """.formatted(placeholders), (RowCallbackHandler) rs ->
                        creditRefreshScheduler.requestCredits(
                                rs.getObject("public_id", UUID.class)), keyIds.toArray());
    }

    /**
     * Lenient per-event timestamp parse: a value Jackson could not bind would
     * have failed the whole batch, so the field arrives as a string and an
     * unparseable one rejects only its own event.
     */
    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.strip()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean containsControlChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static int nonNegative(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private static long nonNegative(Long value) {
        return value == null || value < 0 ? 0 : value;
    }
}
