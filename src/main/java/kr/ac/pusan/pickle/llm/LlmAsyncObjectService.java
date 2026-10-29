package kr.ac.pusan.pickle.llm;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.dto.LlmObjectBindRequest;
import kr.ac.pusan.pickle.llm.dto.LlmObjectBindResponse;
import kr.ac.pusan.pickle.llm.dto.LlmObjectIntentRequest;
import kr.ac.pusan.pickle.llm.dto.LlmObjectIntentResponse;
import kr.ac.pusan.pickle.llm.dto.LlmObjectLookupRequest;
import kr.ac.pusan.pickle.llm.dto.LlmObjectLookupResponse;
import kr.ac.pusan.pickle.llm.dto.LlmObjectReleaseRequest;
import kr.ac.pusan.pickle.llm.dto.LlmObjectReleaseResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The async-object ownership ledger ({@code llm_async_objects}). The gateway
 * writes an intent before it submits to the vendor, binds the vendor id after
 * the vendor accepts, and looks ownership up before it forwards anything that
 * names an object. The vendor scopes these objects to a workspace shared by
 * every key of a business account, so this table is the only thing keeping
 * one key out of another's objects.
 */
@Service
public class LlmAsyncObjectService {

    static final String STATE_OWNED = "OWNED";

    private final JdbcTemplate jdbc;

    public LlmAsyncObjectService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes a PENDING row for an active, unexpired key bound to a business
     * account. Checking status here closes the snapshot's polling window for
     * new objects: a key revoked a second ago may still authenticate at the
     * gateway, but it cannot start anything.
     */
    @Transactional
    public LlmObjectIntentResponse intent(LlmObjectIntentRequest request) {
        List<KeyRow> keys = jdbc.query("""
                select id, openrouter_account_id,
                       status = 'ACTIVE' and (expires_at is null or expires_at > now()) as usable
                from llm_api_keys where public_id = ?
                """,
                (rs, i) -> new KeyRow(rs.getLong("id"),
                        (Long) rs.getObject("openrouter_account_id"), rs.getBoolean("usable")),
                request.keyId());
        if (keys.isEmpty() || !keys.getFirst().usable()) {
            throw new LlmObjectRefusedException(HttpStatus.CONFLICT, "KEY_NOT_ACTIVE");
        }
        KeyRow key = keys.getFirst();
        if (key.accountId() == null) {
            throw new LlmObjectRefusedException(HttpStatus.CONFLICT, "NO_ACCOUNT");
        }
        UUID intentId = jdbc.queryForObject("""
                insert into llm_async_objects (key_id, openrouter_account_id, kind, event_id)
                values (?, ?, ?, ?)
                returning public_id
                """, UUID.class, key.id(), key.accountId(), request.kind().name(), request.eventId());
        return new LlmObjectIntentResponse(intentId);
    }

    /**
     * Attaches the vendor id to an intent. Idempotent for the same id, so a
     * bind whose response was lost can be sent again. A vendor id another row
     * of the same key already owns answers success and releases this intent as
     * a duplicate; one owned by a different key is refused, and the gateway
     * must then not undo the submit, because the object is that key's.
     */
    @Transactional
    public LlmObjectBindResponse bind(LlmObjectBindRequest request) {
        List<IntentRow> intents = jdbc.query("""
                select o.id, o.key_id, o.openrouter_account_id, o.state, o.vendor_object_id,
                       o.release_reason
                from llm_async_objects o
                join llm_api_keys k on k.id = o.key_id
                where o.public_id = ? and k.public_id = ? and o.kind = ?
                for update of o
                """,
                (rs, i) -> new IntentRow(rs.getLong("id"), rs.getLong("key_id"),
                        rs.getLong("openrouter_account_id"), rs.getString("state"),
                        rs.getString("vendor_object_id"), rs.getString("release_reason")),
                request.intentId(), request.keyId(), request.kind().name());
        if (intents.isEmpty()) {
            throw new LlmObjectRefusedException(HttpStatus.NOT_FOUND, "INTENT_UNKNOWN");
        }
        IntentRow intent = intents.getFirst();
        switch (intent.state()) {
            case STATE_OWNED -> {
                if (intent.vendorObjectId().equals(request.objectId())) {
                    return new LlmObjectBindResponse(STATE_OWNED);
                }
                throw new LlmObjectRefusedException(HttpStatus.CONFLICT, "INTENT_BOUND_ELSEWHERE");
            }
            case "RELEASED" -> {
                // Only a duplicate release is answerable again: its earlier
                // bind succeeded, and the owning row is checked once more below.
                if (!"DUPLICATE".equals(intent.releaseReason())) {
                    throw new LlmObjectRefusedException(HttpStatus.NOT_FOUND, "INTENT_UNKNOWN");
                }
            }
            default -> {
            }
        }

        // Serialise binds of the same vendor id, so two concurrent binds see
        // each other here rather than one failing on the unique index.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null,
                intent.accountId() + "/" + request.kind().name() + "/" + request.objectId());
        List<Long> holders = jdbc.queryForList("""
                select key_id from llm_async_objects
                where openrouter_account_id = ? and kind = ? and vendor_object_id = ?
                  and state <> 'RELEASED' and id <> ?
                """, Long.class, intent.accountId(), request.kind().name(), request.objectId(),
                intent.id());
        if (!holders.isEmpty()) {
            if (holders.getFirst().longValue() != intent.keyId()) {
                // A duplicate intent whose original has since passed to
                // another key is simply gone, not a conflict of this bind.
                if ("RELEASED".equals(intent.state())) {
                    throw new LlmObjectRefusedException(HttpStatus.NOT_FOUND, "INTENT_UNKNOWN");
                }
                throw new LlmObjectRefusedException(HttpStatus.CONFLICT, "OBJECT_TAKEN");
            }
            if (!"RELEASED".equals(intent.state())) {
                jdbc.update("""
                        update llm_async_objects
                        set state = 'RELEASED', release_reason = 'DUPLICATE', released_at = now()
                        where id = ?
                        """, intent.id());
            }
            return new LlmObjectBindResponse(STATE_OWNED);
        }
        if ("RELEASED".equals(intent.state())) {
            // The row the duplicate pointed at has since been released.
            throw new LlmObjectRefusedException(HttpStatus.NOT_FOUND, "INTENT_UNKNOWN");
        }
        jdbc.update("""
                update llm_async_objects
                set state = 'OWNED', vendor_object_id = ?, bound_at = now(),
                    size_bytes = ?, expires_at = ?
                where id = ?
                """, request.objectId(), request.sizeBytes(), timestamp(request.expiresAt()),
                intent.id());
        return new LlmObjectBindResponse(STATE_OWNED);
    }

    /** The asked ids this key owns. Nothing else is distinguishable. */
    @Transactional(readOnly = true)
    public LlmObjectLookupResponse lookup(LlmObjectLookupRequest request) {
        List<String> owned = jdbc.queryForList("""
                select o.vendor_object_id
                from llm_async_objects o
                join llm_api_keys k on k.id = o.key_id
                where k.public_id = ? and o.kind = ? and o.state = 'OWNED'
                  and o.vendor_object_id = any (?)
                """, String.class, request.keyId(), request.kind().name(),
                request.objectIds().toArray(String[]::new));
        return new LlmObjectLookupResponse(owned);
    }

    /**
     * Releases an owned object by vendor id, or an unbound intent by intent
     * id. Idempotent: a row already released, or another key's, answers
     * {@code false}.
     */
    @Transactional
    public LlmObjectReleaseResponse release(LlmObjectReleaseRequest request) {
        boolean byObject = request.objectId() != null;
        boolean byIntent = request.intentId() != null;
        boolean objectReason = request.reason() != LlmObjectReleaseReason.NOT_CREATED;
        if (byObject == byIntent || byObject != objectReason) {
            throw new LlmObjectRefusedException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "RELEASE_TARGET_INVALID");
        }
        int changed;
        if (byObject) {
            changed = jdbc.update("""
                    update llm_async_objects o
                    set state = 'RELEASED', release_reason = ?, released_at = now()
                    from llm_api_keys k
                    where k.id = o.key_id and k.public_id = ? and o.kind = ?
                      and o.vendor_object_id = ? and o.state = 'OWNED'
                    """, request.reason().name(), request.keyId(), request.kind().name(),
                    request.objectId());
        } else {
            changed = jdbc.update("""
                    update llm_async_objects o
                    set state = 'RELEASED', release_reason = 'NOT_CREATED', released_at = now()
                    from llm_api_keys k
                    where k.id = o.key_id and k.public_id = ? and o.kind = ?
                      and o.public_id = ? and o.state = 'PENDING'
                    """, request.keyId(), request.kind().name(), request.intentId());
        }
        return new LlmObjectReleaseResponse(changed > 0);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private record KeyRow(long id, Long accountId, boolean usable) {
    }

    private record IntentRow(long id, long keyId, long accountId, String state,
            String vendorObjectId, String releaseReason) {
    }
}
