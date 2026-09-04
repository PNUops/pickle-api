package kr.ac.pusan.pickle.llm;

import static kr.ac.pusan.pickle.llm.LlmUsageService.MAX_EVENT_ID_LENGTH;
import static kr.ac.pusan.pickle.llm.LlmUsageService.containsControlChars;
import static kr.ac.pusan.pickle.llm.LlmUsageService.parseInstant;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.dto.LlmBodiesRequest;
import kr.ac.pusan.pickle.llm.dto.LlmBodiesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Storage for opted-in prompt and response text.
 *
 * <p>Three constraints shape this, and none of them is obvious from the shape
 * of the code:
 *
 * <ul>
 *   <li><b>A problem with one record is never a 4xx.</b> Same reasoning as
 *       {@link LlmUsageService}, and stronger here: the bodies channel holds
 *       nothing on disk and never retries a refused batch, so a non-2xx is
 *       text destroyed rather than text delayed. Every per-record failure is
 *       caught inside the loop and counted.</li>
 *   <li><b>Nothing unattributed is stored.</b> A usage event with no key is
 *       kept, because it evidences a client looping on a bad token. A body
 *       with no key could never be read by anyone — every read path is scoped
 *       to a key's access list — so keeping it would only hoard personal text
 *       that answers to nobody.</li>
 *   <li><b>This path takes no lock it does not already need.</b> It does not
 *       touch the generation counter and does not update {@code llm_api_keys}
 *       — notably not {@code last_used_at}, tempting as that is. Stamping here
 *       would make body ingest the one writer that reaches a key row without
 *       taking the counter first, which is exactly the ordering
 *       {@link LlmUsageService} documents as deadlock-prone. Its key lookup is
 *       a plain select. Each insert still takes the foreign key's KEY SHARE on
 *       the key row, but only for its own statement, and that is compatible
 *       with the FOR NO KEY UPDATE the quota and last-used writers take.</li>
 * </ul>
 */
@Service
public class LlmBodyIngestService {

    private static final Logger log = LoggerFactory.getLogger(LlmBodyIngestService.class);

    private static final String INSERT_SQL = """
            insert into llm_request_bodies (public_id, event_id, key_id, request_enc,
                    response_enc, request_truncated, response_truncated, request_bytes,
                    response_bytes, cipher_key_id, requested_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (event_id) do nothing
            returning id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final LlmBodyCipher cipher;
    private final ObjectMapper objectMapper;

    public LlmBodyIngestService(JdbcTemplate jdbcTemplate, LlmBodyCipher cipher,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
    }

    /**
     * Deliberately not {@code @Transactional}. Wrapping the batch looks tidier
     * and is wrong here: Postgres aborts a transaction on a statement error, so
     * catching one record's failure and carrying on leaves every later
     * statement failing too, and the COMMIT then rolls back silently while the
     * reply still reports what it accepted. The gateway discards a batch it was
     * told about, so that combination destroys the text and calls it success.
     * Each record therefore stands on its own statement, which is what "one
     * record's failure must not cost the batch" actually requires.
     */
    public LlmBodiesResponse ingest(LlmBodiesRequest request) {
        List<LlmBodiesRequest.BodyRecord> records =
                request.records() == null ? List.of() : request.records();
        if (records.isEmpty()) {
            return new LlmBodiesResponse(0, 0, 0, 0);
        }
        // Fail closed on the keyring rather than on startup. Capture is off by
        // default, so a missing key must not stop the service booting — but it
        // must never mean "store the text in the clear either". One line per
        // batch, never per record: a misconfigured host would otherwise write
        // a log line for every prompt it refuses to keep.
        String cipherKeyId = cipher.writeKeyId();
        if (cipherKeyId == null) {
            log.warn("LLM bodies batch: keyring unconfigured, {} record(s) discarded unstored",
                    records.size());
            return new LlmBodiesResponse(0, 0, 0, records.size());
        }

        Map<String, KeyRow> keys = resolveKeys(records);
        int accepted = 0;
        int duplicates = 0;
        int rejected = 0;
        int skipped = 0;
        for (LlmBodiesRequest.BodyRecord record : records) {
            if (record == null) {
                rejected++;
                continue;
            }
            String eventId = record.eventUuid() == null ? null : record.eventUuid().strip();
            Instant requestedAt = parseInstant(record.requestedAt());
            if (eventId == null || eventId.isBlank() || eventId.length() > MAX_EVENT_ID_LENGTH
                    || containsControlChars(eventId) || requestedAt == null) {
                rejected++;
                continue;
            }
            KeyRow key = record.keyId() == null ? null : keys.get(record.keyId().strip());
            if (key == null) {
                skipped++;
                continue;
            }
            // The authoritative flag is this row, not the snapshot the gateway
            // was holding. It polls, so records captured just before somebody
            // turned recording off are genuinely in flight; storing them would
            // keep text from the moment after its owner said stop.
            if (!key.recordBodies()) {
                skipped++;
                continue;
            }
            String requestJson = canonicalJson(record.request());
            String response = record.response();
            if (requestJson == null && (response == null || response.isEmpty())) {
                // Metadata with no text duplicates the usage event and nothing more.
                skipped++;
                continue;
            }
            try {
                if (insert(record, eventId, key, requestJson, response, requestedAt,
                        cipherKeyId)) {
                    accepted++;
                } else {
                    duplicates++;
                }
            } catch (RuntimeException e) {
                // One record's encryption or write must not cost the batch.
                rejected++;
                log.warn("LLM bodies batch: a record could not be stored ({})",
                        e.getClass().getSimpleName());
            }
        }
        if (rejected > 0 || skipped > 0) {
            log.warn("LLM bodies batch: accepted {}, duplicates {}, rejected {}, skipped {}",
                    accepted, duplicates, rejected, skipped);
        } else {
            log.info("LLM bodies batch: accepted {}, duplicates {}", accepted, duplicates);
        }
        return new LlmBodiesResponse(accepted, duplicates, rejected, skipped);
    }

    /** True when the row was inserted; false means this event id already exists. */
    private boolean insert(LlmBodiesRequest.BodyRecord record, String eventId, KeyRow key,
            String requestJson, String response, Instant requestedAt, String cipherKeyId) {
        String requestEnc = requestJson == null ? null
                : cipher.encrypt(key.publicId(), eventId, LlmBodyCipher.Field.REQUEST, requestJson);
        String responseEnc = response == null || response.isEmpty() ? null
                : cipher.encrypt(key.publicId(), eventId, LlmBodyCipher.Field.RESPONSE, response);
        Long inserted = jdbcTemplate.query(INSERT_SQL,
                rs -> rs.next() ? rs.getLong("id") : null,
                UUID.randomUUID(), eventId, key.id(), requestEnc, responseEnc,
                Boolean.TRUE.equals(record.requestTruncated()),
                Boolean.TRUE.equals(record.responseTruncated()),
                byteLength(requestJson), byteLength(response), cipherKeyId,
                java.sql.Timestamp.from(requestedAt));
        return inserted != null;
    }

    /**
     * Serialises the captured request back to canonical JSON, or null when
     * there is no prompt to store.
     *
     * <p>A JSON {@code null} counts as absent. It arrives as a {@code NullNode}
     * rather than a Java null, so an identity check alone would serialise it to
     * the four characters "null" and store a record whose prompt is a literal
     * null -- text nobody wrote, kept for thirty days.</p>
     */
    private String canonicalJson(JsonNode request) {
        if (request == null || request.isMissingNode() || request.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static int byteLength(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * One batched lookup from the gateway's reported key ids — the keys' public
     * UUIDs — to the internal row and its current recording flag. Revoked keys
     * resolve: their rows live on soft delete precisely so the people who used
     * them can still read what they did.
     */
    private Map<String, KeyRow> resolveKeys(List<LlmBodiesRequest.BodyRecord> records) {
        Map<String, UUID> parsed = new HashMap<>();
        Set<UUID> wanted = new LinkedHashSet<>();
        for (LlmBodiesRequest.BodyRecord record : records) {
            if (record == null || record.keyId() == null || record.keyId().isBlank()) {
                continue;
            }
            String reported = record.keyId().strip();
            if (parsed.containsKey(reported)) {
                continue;
            }
            try {
                UUID publicId = UUID.fromString(reported);
                parsed.put(reported, publicId);
                wanted.add(publicId);
            } catch (IllegalArgumentException e) {
                // Not an id this side ever issued; the record is skipped.
            }
        }
        if (wanted.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(wanted.size(), "?"));
        Map<UUID, KeyRow> byPublicId = new HashMap<>();
        jdbcTemplate.query(
                "select id, public_id, record_bodies from llm_api_keys where public_id in ("
                        + placeholders + ")",
                rs -> {
                    UUID publicId = rs.getObject("public_id", UUID.class);
                    byPublicId.put(publicId, new KeyRow(rs.getLong("id"), publicId,
                            rs.getBoolean("record_bodies")));
                }, wanted.toArray());
        Map<String, KeyRow> resolved = new HashMap<>();
        for (Map.Entry<String, UUID> entry : parsed.entrySet()) {
            KeyRow row = byPublicId.get(entry.getValue());
            if (row != null) {
                resolved.put(entry.getKey(), row);
            }
        }
        return resolved;
    }

    private record KeyRow(long id, UUID publicId, boolean recordBodies) {
    }
}
