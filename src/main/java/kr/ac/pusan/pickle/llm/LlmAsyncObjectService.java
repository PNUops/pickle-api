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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

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

    /** Matches the column check and the internal link's member bound. */
    static final int MAX_OBJECT_ID_LENGTH = 255;

    private static final Logger log = LoggerFactory.getLogger(LlmAsyncObjectService.class);

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

    /**
     * Binds an intent from the usage event of its submit: the late bind. Runs
     * inside the usage ingest transaction, on a first insert and on a
     * duplicate alike, because redelivery is normal and the restore replay
     * depends on it.
     *
     * <p>Never throws for anything the member says. A member it cannot use is
     * logged and skipped: accounting must not be lost to a ledger defect, and
     * the row then stays {@code PENDING} for reconciliation like an
     * unconfirmed submit. The writes run under a savepoint so that an
     * unexpected database error rolls back this member alone and not the
     * events already inserted in the same batch; a transient one (a deadlock,
     * a lock timeout) fails the batch instead, so the gateway retries it.
     *
     * <p>Three cases. The intent row is still {@code PENDING}: bind it, as
     * {@code OWNED} or, when the gateway undid the submit, as
     * {@code RELEASED}/{@code COMPENSATED}. The row is already bound to this
     * object: nothing to do, which is the ordinary case because the member
     * travels on every accepted submit. The row is missing: a restore lost it,
     * and it is recreated from the event so the owner gets the object back.
     */
    public void bindFromUsage(long keyRowId, String eventId, ObjectMember member) {
        jdbc.execute("savepoint llm_object_late_bind");
        try {
            bindFromUsageUnguarded(keyRowId, eventId, member);
            jdbc.execute("release savepoint llm_object_late_bind");
        } catch (TransientDataAccessException e) {
            // A deadlock, a lock timeout or a serialization failure would
            // succeed on a retry, and skipping it would lose the bind for good:
            // the usage row commits, so no redelivery brings the member back.
            // Failing the batch makes the gateway send it again.
            jdbc.execute("rollback to savepoint llm_object_late_bind");
            throw e;
        } catch (RuntimeException e) {
            jdbc.execute("rollback to savepoint llm_object_late_bind");
            log.warn("late bind of {} {} from usage event {} failed; the row stays as it was",
                    member.kind(), member.objectId(), eventId, e);
        }
    }

    private void bindFromUsageUnguarded(long keyRowId, String eventId, ObjectMember member) {
        Long accountId = jdbc.query(
                "select openrouter_account_id from llm_api_keys where id = ?",
                rs -> rs.next() ? (Long) rs.getObject(1) : null, keyRowId);
        if (accountId == null) {
            log.warn("late bind from usage event {}: the key has no business account", eventId);
            return;
        }
        String kind = member.kind().name();
        // The intent row first, then the vendor-id lock: the order the
        // synchronous bind takes them in, so a bind still running when its
        // submit's usage event arrives waits rather than deadlocks.
        List<IntentRow> intents = member.intentId() == null ? List.of() : jdbc.query("""
                select id, key_id, openrouter_account_id, state, vendor_object_id, release_reason
                from llm_async_objects
                where public_id = ? and key_id = ? and kind = ?
                for update
                """,
                (rs, i) -> new IntentRow(rs.getLong("id"), rs.getLong("key_id"),
                        rs.getLong("openrouter_account_id"), rs.getString("state"),
                        rs.getString("vendor_object_id"), rs.getString("release_reason")),
                member.intentId(), keyRowId, kind);
        // The same lock as the synchronous bind, so the two never race on the
        // unique index of live vendor ids.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null,
                accountId + "/" + kind + "/" + member.objectId());
        List<Long> holders = jdbc.queryForList("""
                select key_id from llm_async_objects
                where openrouter_account_id = ? and kind = ? and vendor_object_id = ?
                  and state <> 'RELEASED'
                """, Long.class, accountId, kind, member.objectId());

        if (!intents.isEmpty()) {
            IntentRow intent = intents.getFirst();
            if (!"PENDING".equals(intent.state())) {
                if (intent.vendorObjectId() != null
                        && !intent.vendorObjectId().equals(member.objectId())) {
                    log.warn("late bind from usage event {}: intent {} is bound to another "
                            + "object id; left as it is", eventId, member.intentId());
                }
                return;
            }
            if (!holders.isEmpty()) {
                if (holders.getFirst().longValue() == keyRowId) {
                    jdbc.update("""
                            update llm_async_objects
                            set state = 'RELEASED', release_reason = 'DUPLICATE',
                                released_at = now()
                            where id = ?
                            """, intent.id());
                } else {
                    log.warn("late bind from usage event {}: {} {} is owned by another key; "
                            + "intent {} stays pending", eventId, kind, member.objectId(),
                            member.intentId());
                }
                return;
            }
            if (member.compensated()) {
                jdbc.update("""
                        update llm_async_objects
                        set state = 'RELEASED', vendor_object_id = ?, bound_at = now(),
                            bound_late = true, release_reason = 'COMPENSATED',
                            released_at = now(), expires_at = ?
                        where id = ?
                        """, member.objectId(), timestamp(member.expiresAt()), intent.id());
            } else {
                jdbc.update("""
                        update llm_async_objects
                        set state = 'OWNED', vendor_object_id = ?, bound_at = now(),
                            bound_late = true, expires_at = ?
                        where id = ?
                        """, member.objectId(), timestamp(member.expiresAt()), intent.id());
            }
            return;
        }

        // No intent row. Either a restore lost it, or it was never this key's.
        // Every submit writes an intent first, so a member without one names
        // nothing a restore could have lost; recreating from it would bring
        // back, on each redelivery, an object its owner has since deleted.
        if (!holders.isEmpty() || member.compensated() || member.intentId() == null) {
            return;
        }
        Integer elsewhere = jdbc.queryForObject(
                "select count(*) from llm_async_objects where public_id = ?",
                Integer.class, member.intentId());
        if (elsewhere != null && elsewhere > 0) {
            log.warn("late bind from usage event {}: intent {} belongs to another key or "
                    + "kind, or was bound already; ignored", eventId, member.intentId());
            return;
        }
        jdbc.update("""
                insert into llm_async_objects (public_id, key_id, openrouter_account_id, kind,
                    state, vendor_object_id, event_id, bound_late, bound_at, expires_at)
                values (?, ?, ?, ?, 'OWNED', ?, ?, true, now(), ?)
                """, member.intentId(), keyRowId, accountId, kind, member.objectId(), eventId,
                timestamp(member.expiresAt()));
    }

    /**
     * The usage event's {@code object} member, read leniently. Null when the
     * member is absent or unusable, so the caller has one case to skip.
     */
    public static @Nullable ObjectMember readMember(@Nullable JsonNode node, String eventId) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isObject()) {
            log.warn("usage event {}: object member is not an object; ignored", eventId);
            return null;
        }
        LlmAsyncObjectKind kind;
        try {
            kind = LlmAsyncObjectKind.valueOf(text(node, "kind"));
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("usage event {}: object member has no known kind; ignored", eventId);
            return null;
        }
        String objectId = text(node, "objectId");
        if (objectId == null || objectId.isEmpty() || objectId.length() > MAX_OBJECT_ID_LENGTH) {
            log.warn("usage event {}: object member has no usable objectId; ignored", eventId);
            return null;
        }
        UUID intentId = null;
        String rawIntent = text(node, "intentId");
        if (rawIntent != null) {
            try {
                intentId = UUID.fromString(rawIntent);
            } catch (IllegalArgumentException e) {
                log.warn("usage event {}: object member intentId is not a UUID; ignored", eventId);
                return null;
            }
        }
        Instant expiresAt = null;
        String rawExpiry = text(node, "expiresAt");
        if (rawExpiry != null) {
            try {
                expiresAt = Instant.parse(rawExpiry);
            } catch (java.time.format.DateTimeParseException e) {
                // An unreadable expiry costs only the sweep's early release.
                expiresAt = null;
            }
        }
        JsonNode compensated = node.get("compensated");
        return new ObjectMember(kind, intentId, objectId,
                compensated != null && compensated.isBoolean() && compensated.booleanValue(),
                expiresAt);
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    /** What a usage event says about the object its submit created. */
    public record ObjectMember(LlmAsyncObjectKind kind, @Nullable UUID intentId, String objectId,
            boolean compensated, @Nullable Instant expiresAt) {
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
