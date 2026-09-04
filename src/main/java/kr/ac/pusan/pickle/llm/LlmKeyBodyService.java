package kr.ac.pusan.pickle.llm;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceAccessResolver;
import kr.ac.pusan.pickle.access.ResourceStanding;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyBodyDetailResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyBodySummaryResponse;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reading captured prompt and response text.
 *
 * <p>The readers are the key's access-list holders and nobody else (operator,
 * 2026-08-11). "The sender only" was asked for first and is not implementable:
 * the gateway authenticates a key, not a person, and a key shared by several
 * people carries no record of which of them sent a request. So the unit of
 * readership is the key.
 *
 * <p>{@link ResourceStanding#requireVisible} already expresses exactly that,
 * including the part that is easy to get wrong — a workspace owner's standing
 * rights are a flag rather than a rung, so owning the workspace a key sits in
 * does not open the key's contents. {@link LlmKeyUsageService} states the
 * principle for usage: content, not standing. Prompt text is the same category
 * and further along it.
 */
@Service
public class LlmKeyBodyService {

    /**
     * How much of each side a list row carries. Enough to recognise a record,
     * far short of reading one — the list is for finding the exchange you want,
     * and the detail call is where reading happens and where the audit trail
     * records it.
     */
    private static final int PREVIEW_CHARS = 200;

    private static final String LIST_SQL = """
            select id, public_id, event_id, request_enc, response_enc, request_truncated,
                   response_truncated, request_bytes, response_bytes, cipher_key_id,
                   requested_at, received_at
              from llm_request_bodies
             where key_id = ?
             order by requested_at desc, id desc
             limit ? offset ?
            """;

    private static final String COUNT_SQL =
            "select count(*) from llm_request_bodies where key_id = ?";

    /**
     * Both terms are load-bearing. Finding the row by its own id alone and then
     * authorizing whatever key it turns out to belong to is the shape that
     * hands one person another person's prompts; the key in the path is the
     * boundary, so the row has to sit under it.
     */
    private static final String DETAIL_SQL = """
            select id, public_id, event_id, request_enc, response_enc, request_truncated,
                   response_truncated, request_bytes, response_bytes, cipher_key_id,
                   requested_at, received_at
              from llm_request_bodies
             where public_id = ? and key_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final LlmApiKeyRepository keyRepository;
    private final ResourceAccessResolver resourceAccessResolver;
    private final LlmBodyCipher cipher;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public LlmKeyBodyService(JdbcTemplate jdbcTemplate, LlmApiKeyRepository keyRepository,
            ResourceAccessResolver resourceAccessResolver, LlmBodyCipher cipher,
            ObjectMapper objectMapper, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.keyRepository = keyRepository;
        this.resourceAccessResolver = resourceAccessResolver;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<LlmKeyBodySummaryResponse> list(AuthenticatedUser actor, UUID keyId,
            int page, int size, String ip) {
        LlmApiKey key = requireReadableKey(actor, keyId);
        Long total = jdbcTemplate.queryForObject(COUNT_SQL, Long.class, key.getId());
        List<LlmKeyBodySummaryResponse> rows = jdbcTemplate.query(LIST_SQL,
                (rs, rowNum) -> summary(read(rs, key.getPublicId())),
                key.getId(), size, (long) page * size);
        auditService.record(actor.id(), actor.role().name(), AuditService.LLM_KEY_BODY_LIST,
                "llm_key", key.getPublicId(),
                Map.of("page", page, "size", size, "returned", rows.size()), ip);
        return PageResponse.of(rows, new PageImpl<>(rows, PageRequest.of(page, size),
                total == null ? 0 : total));
    }

    @Transactional(readOnly = true)
    public LlmKeyBodyDetailResponse get(AuthenticatedUser actor, UUID keyId, UUID bodyId,
            String ip) {
        LlmApiKey key = requireReadableKey(actor, keyId);
        List<Row> rows = jdbcTemplate.query(DETAIL_SQL,
                (rs, rowNum) -> read(rs, key.getPublicId()), bodyId, key.getId());
        if (rows.isEmpty()) {
            throw LlmKeyResourceAdapter.MESSAGES.notFound();
        }
        Row row = rows.get(0);
        auditService.record(actor.id(), actor.role().name(), AuditService.LLM_KEY_BODY_READ,
                "llm_key_body", row.publicId(), Map.of("keyId", key.getPublicId().toString()), ip);
        boolean readable = cipher.canRead(row.cipherKeyId());
        return new LlmKeyBodyDetailResponse(row.publicId(), row.eventId(), row.requestedAt(),
                row.receivedAt(), row.requestTruncated(), row.responseTruncated(),
                row.requestBytes(), row.responseBytes(), readable,
                readable ? requestNode(row) : null,
                readable ? decrypt(row, LlmBodyCipher.Field.RESPONSE, row.responseEnc()) : null);
    }

    /**
     * The key must exist and the caller must hold a grant on it. Recording
     * being switched off does not close this: the switch governs what is
     * captured next, not what may be read, and hiding rows that are still
     * stored would leave people unable to see what they had already produced.
     */
    private LlmApiKey requireReadableKey(AuthenticatedUser actor, UUID keyId) {
        LlmApiKey key = keyRepository.findByPublicId(keyId)
                .orElseThrow(() -> LlmKeyResourceAdapter.MESSAGES.notFound());
        ResourceStanding standing = resourceAccessResolver.standing(ResourceType.LLM_API_KEY,
                key.getId(), key.getWorkspaceId(), actor.id());
        standing.requireVisible(LlmKeyResourceAdapter.MESSAGES);
        return key;
    }

    private LlmKeyBodySummaryResponse summary(Row row) {
        boolean readable = cipher.canRead(row.cipherKeyId());
        String request = readable ? plainRequestPreviewSource(row) : null;
        String response = readable
                ? decrypt(row, LlmBodyCipher.Field.RESPONSE, row.responseEnc()) : null;
        return new LlmKeyBodySummaryResponse(row.publicId(), row.eventId(), row.requestedAt(),
                row.receivedAt(), row.requestTruncated(), row.responseTruncated(),
                row.requestBytes(), row.responseBytes(), readable,
                preview(request), preview(response));
    }

    /**
     * The prompt as text for previewing. A messages array previews better as
     * its concatenated text than as raw JSON, but building that here would put
     * a second, drifting interpretation of the shape next to the one the client
     * already has to implement for the detail view. The raw JSON is honest and
     * recognisable at 200 characters.
     */
    private String plainRequestPreviewSource(Row row) {
        return decrypt(row, LlmBodyCipher.Field.REQUEST, row.requestEnc());
    }

    private JsonNode requestNode(Row row) {
        String json = decrypt(row, LlmBodyCipher.Field.REQUEST, row.requestEnc());
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            // Stored text that will not parse is a defect, not a 500 for the
            // reader: the rest of the record is still worth showing.
            return null;
        }
    }

    private String decrypt(Row row, LlmBodyCipher.Field field, String stored) {
        if (stored == null) {
            return null;
        }
        try {
            return cipher.decrypt(row.keyPublicId(), row.eventId(), field, stored);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String preview(String text) {
        if (text == null) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        if (flat.isEmpty()) {
            return null;
        }
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS);
    }

    /**
     * The key's public id travels into every row because it is half the AAD:
     * a ciphertext only opens under the key it was written for, so a row
     * repointed at another key in the database fails to decrypt rather than
     * showing up in that key's list.
     */
    private Row read(java.sql.ResultSet rs, UUID keyPublicId) throws java.sql.SQLException {
        return new Row(
                rs.getObject("public_id", UUID.class),
                rs.getString("event_id"),
                rs.getString("request_enc"),
                rs.getString("response_enc"),
                rs.getBoolean("request_truncated"),
                rs.getBoolean("response_truncated"),
                rs.getInt("request_bytes"),
                rs.getInt("response_bytes"),
                rs.getString("cipher_key_id"),
                rs.getTimestamp("requested_at").toInstant(),
                rs.getTimestamp("received_at").toInstant(),
                keyPublicId);
    }

    private record Row(UUID publicId, String eventId, String requestEnc, String responseEnc,
            boolean requestTruncated, boolean responseTruncated, int requestBytes,
            int responseBytes, String cipherKeyId, Instant requestedAt, Instant receivedAt,
            UUID keyPublicId) {
    }
}
