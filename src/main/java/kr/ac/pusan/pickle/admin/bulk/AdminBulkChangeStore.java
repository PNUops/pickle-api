package kr.ac.pusan.pickle.admin.bulk;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeApplyResponse;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeOperationResponse.AdminBulkChangeEffect;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangePreviewResponse;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeRequest;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Storage and atomic receipt identity, without a second execution path.
 *
 * <p>A null result and committed timestamp represent an unfinished claim
 * inside the business transaction, never a public committed receipt. A null
 * effect target denotes an operation-level callback such as the summary audit.
 * A missing external reference establishes no queue acceptance (deduplication
 * and unknown outcomes included); a missing observation time means the later
 * outcome was not recorded. None of these nulls establishes external completion.
 */
@Component
class AdminBulkChangeStore {
    record Preview(AdminBulkChangeRequest request, AdminBulkChangePreviewResponse response) { }
    record Operation(UUID id, UUID previewId, String binding, @Nullable AdminBulkChangeApplyResponse result,
            @Nullable Instant committedAt) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final org.jobrunr.storage.StorageProvider jobStorage;
    private final TransactionTemplate observationTransaction;

    AdminBulkChangeStore(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock, PlatformTransactionManager transactions, org.jobrunr.storage.StorageProvider jobStorage) {
        this.jdbc = jdbc; this.mapper = mapper; this.clock = clock; this.jobStorage = jobStorage;
        this.observationTransaction = new TransactionTemplate(transactions);
        this.observationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    AdminBulkChangePreviewResponse savePreview(long actorId, AdminBulkChangeRequest request,
            List<kr.ac.pusan.pickle.admin.dto.AdminBulkChangePreviewItem> items) {
        Instant now = clock.instant();
        AdminBulkChangeRequest savedRequest = new AdminBulkChangeRequest(request.targetType(), request.targetIds(),
                request.change(), null, null, null, null);
        AdminBulkChangePreviewResponse response = new AdminBulkChangePreviewResponse(UUID.randomUUID(), now,
                now.plus(Duration.ofHours(24)), request.targetType(), request.change(), items, null);
        jdbc.update("""
                insert into admin_bulk_change_previews(id,actor_id,target_type,change_kind,request,preview,created_at,expires_at)
                values(?,?,?,?,?::jsonb,?::jsonb,?,?)
                """, response.previewId(), actorId, request.targetType().name(), request.change().kind().name(),
                mapper.writeValueAsString(savedRequest), mapper.writeValueAsString(response), timestamp(now), timestamp(response.expiresAt()));
        return response;
    }

    Preview preview(long actorId, UUID id) {
        Preview saved = jdbc.query("select request::text,preview::text from admin_bulk_change_previews where id=? and actor_id=?",
                (rs, row) -> new Preview(mapper.readValue(rs.getString(1), AdminBulkChangeRequest.class),
                        mapper.readValue(rs.getString(2), AdminBulkChangePreviewResponse.class)), id, actorId)
                .stream().findFirst().orElseThrow(AdminBulkChangeStore::notFound);
        UUID consumed = jdbc.query("select id from admin_bulk_change_operations where preview_id=? and actor_id=? and result is not null",
                (rs, row) -> rs.getObject(1, UUID.class), id, actorId).stream().findFirst().orElse(null);
        AdminBulkChangePreviewResponse response = saved.response();
        return new Preview(saved.request(), new AdminBulkChangePreviewResponse(response.previewId(), response.createdAt(),
                response.expiresAt(), response.targetType(), response.change(), response.items(), consumed));
    }

    @Nullable Operation existing(long actorId, UUID id) {
        return jdbc.query("select * from admin_bulk_change_operations where id=? and actor_id=? for update",
                this::operation, id, actorId).stream().findFirst().orElse(null);
    }

    Operation read(long actorId, UUID id) {
        return jdbc.query("select * from admin_bulk_change_operations where id=? and actor_id=? and result is not null",
                this::operation, id, actorId).stream().findFirst().orElseThrow(AdminBulkChangeStore::notFound);
    }

    @Nullable Operation claim(long actorId, AdminBulkChangeRequest request, String binding) {
        int inserted = jdbc.update("""
                insert into admin_bulk_change_operations(id,preview_id,actor_id,binding,created_at)
                values(?,?,?,?,?) on conflict do nothing
                """, request.operationId(), request.previewId(), actorId, binding, timestamp(clock.instant()));
        if (inserted == 1) { return null; }
        Operation existing = existing(actorId, request.operationId());
        if (existing != null) { return existing; }
        boolean usedOwnPreview = Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from admin_bulk_change_operations where preview_id=? and actor_id=?)",
                Boolean.class, request.previewId(), actorId));
        if (usedOwnPreview) { throw conflict("이 미리보기는 이미 다른 적용에 사용됐습니다. 저장된 결과를 다시 조회해 주세요."); }
        throw notFound();
    }

    void complete(UUID id, AdminBulkChangeApplyResponse response) {
        jdbc.update("update admin_bulk_change_operations set result=?::jsonb,committed_at=? where id=?",
                mapper.writeValueAsString(response), timestamp(clock.instant()), id);
    }

    String binding(AdminBulkChangeRequest request) {
        try {
            byte[] bytes = canonical(mapper.valueToTree(request)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private String canonical(tools.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            java.util.List<String> names = new java.util.ArrayList<>();
            names.addAll(node.propertyNames());
            java.util.Collections.sort(names);
            return names.stream().map(name -> mapper.writeValueAsString(name) + ":" + canonical(node.get(name)))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (node.isArray()) {
            java.util.List<String> values = new java.util.ArrayList<>();
            node.forEach(value -> values.add(canonical(value)));
            return String.join(",", values).transform(value -> "[" + value + "]");
        }
        return node.toString();
    }

    void validatePreview(Preview preview, AdminBulkChangeRequest request) {
        AdminBulkChangeRequest saved = preview.request();
        if (saved.targetType() != request.targetType() || !saved.targetIds().equals(request.targetIds())
                || !mapper.valueToTree(saved.change()).equals(mapper.valueToTree(request.change()))) {
            throw conflict("저장된 미리보기와 대상 또는 변경 내용이 다릅니다.");
        }
        Map<UUID, String> fingerprints = preview.response().items().stream().collect(java.util.stream.Collectors.toMap(
                kr.ac.pusan.pickle.admin.dto.AdminBulkChangePreviewItem::targetId,
                kr.ac.pusan.pickle.admin.dto.AdminBulkChangePreviewItem::fingerprint));
        if (!fingerprints.equals(request.fingerprints())) { throw conflict("저장된 미리보기의 확인값과 일치하지 않습니다."); }
        if (!clock.instant().isBefore(preview.response().expiresAt())) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("previewId", "미리보기의 새 적용 가능 시간이 지났습니다. 다시 미리보기를 만들어 주세요.")));
        }
    }

    List<UUID> recent(long actorId, int limit) {
        return jdbc.queryForList("select id from admin_bulk_change_operations where actor_id=? and result is not null order by created_at desc,id limit ?",
                UUID.class, actorId, limit);
    }

    UUID registerEffect(UUID operationId, @Nullable UUID targetId, String label) {
        UUID id = UUID.randomUUID();
        String kind = label.startsWith("audit ") ? "AUDIT" : label.startsWith("enqueue ") ? "QUEUE"
                : label.startsWith("openrouter ") ? "EXTERNAL_CALLBACK" : "CALLBACK";
        jdbc.update("insert into admin_bulk_change_effects(id,operation_id,target_id,kind,label,outcome,registered_at) values(?,?,?,?,?,'REGISTERED',?)",
                id, operationId, targetId, kind, label, timestamp(clock.instant()));
        return id;
    }

    void observeEffect(UUID id, boolean success, @Nullable String reference, boolean acceptedQueue) {
        observationTransaction.executeWithoutResult(tx -> jdbc.update(
                "update admin_bulk_change_effects set outcome=case when kind='AUDIT' and ?='UNKNOWN' then 'COMPLETED' else ? end,external_reference=?,observed_at=? where id=?",
                !success ? "FAILED" : acceptedQueue ? "ACCEPTED" : "UNKNOWN",
                !success ? "FAILED" : acceptedQueue ? "ACCEPTED" : "UNKNOWN", reference, timestamp(clock.instant()), id));
    }

    List<AdminBulkChangeEffect> effects(UUID operationId) {
        return jdbc.query("select * from admin_bulk_change_effects where operation_id=? order by registered_at,id",
                (rs, row) -> new AdminBulkChangeEffect(rs.getObject("id", UUID.class), rs.getObject("target_id", UUID.class),
                        rs.getString("kind"), rs.getString("outcome"), rs.getString("external_reference"),
                        rs.getTimestamp("registered_at").toInstant(), rs.getTimestamp("observed_at") == null ? null
                                : rs.getTimestamp("observed_at").toInstant(), null, null), operationId);
    }

    AdminBulkChangeEffect observeQueue(AdminBulkChangeEffect effect) {
        if (!effect.kind().equals("QUEUE") || effect.externalReference() == null) { return effect; }
        String state;
        try { state = jobStorage.getJobById(UUID.fromString(effect.externalReference())).getState().name(); }
        catch (RuntimeException unavailable) { state = "UNKNOWN"; }
        return new AdminBulkChangeEffect(effect.id(), effect.targetId(), effect.kind(), effect.outcome(),
                effect.externalReference(), effect.registeredAt(), effect.observedAt(), state, clock.instant());
    }

    private Operation operation(ResultSet rs, int row) throws SQLException {
        String result = rs.getString("result");
        return new Operation(rs.getObject("id", UUID.class), rs.getObject("preview_id", UUID.class), rs.getString("binding"),
                result == null ? null : mapper.readValue(result, AdminBulkChangeApplyResponse.class),
                rs.getTimestamp("committed_at") == null ? null : rs.getTimestamp("committed_at").toInstant());
    }

    static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.ADMIN_BULK_CHANGE_CONFLICT, "일괄 변경 확인이 필요합니다", detail);
    }
    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND, "리소스를 찾을 수 없습니다", "현재 계정으로 조회할 수 있는 일괄 변경 기록이 없습니다.");
    }
    private static OffsetDateTime timestamp(Instant instant) { return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
