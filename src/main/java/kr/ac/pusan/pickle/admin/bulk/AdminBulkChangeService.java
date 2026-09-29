package kr.ac.pusan.pickle.admin.bulk;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeApplyItem;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeApplyResponse;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeKind;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangePreviewItem;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangePreviewResponse;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeReason;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeRequest;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeResult;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeSpec;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.tx.AfterCommit;
import kr.ac.pusan.pickle.llm.LlmGatewayGenerations;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * One administrator change applied to many targets: contract ops
 * {@code previewAdminBulkChange} and {@code applyAdminBulkChange}.
 *
 * <p>Only the chosen fields change, and the server never trusts the preview:
 * the apply recomputes every judgment after taking whatever lock the family
 * needs, and a target whose fingerprint no longer matches is reported STALE
 * and left alone. A target the actor may not touch is reported per target,
 * in the same words the single path would use, never as a whole-call
 * failure. There is no undo.
 *
 * <p>Lock order everywhere: the gateway generation row first, then target
 * rows by internal id. The generation is spent once per apply that writes
 * any LLM key, and never by a preview.
 */
@Service
public class AdminBulkChangeService {

    private final Map<AdminBulkChangeKind, BulkChangeHandler<?>> handlers =
            new EnumMap<>(AdminBulkChangeKind.class);
    private final LlmGatewayGenerations generations;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AdminBulkChangeService(List<BulkChangeHandler<?>> handlers,
            LlmGatewayGenerations generations, AuditService auditService,
            ObjectMapper objectMapper, Clock clock) {
        for (BulkChangeHandler<?> handler : handlers) {
            for (AdminBulkChangeKind kind : handler.kinds()) {
                this.handlers.put(kind, handler);
            }
        }
        this.generations = generations;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminBulkChangePreviewResponse preview(AuthenticatedUser actor,
            AdminBulkChangeRequest request) {
        Instant now = clock.instant();
        BulkChangeHandler<?> handler = validate(request, false, now);
        return new AdminBulkChangePreviewResponse(preview(handler, actor, request, now));
    }

    @Transactional
    public AdminBulkChangeApplyResponse apply(AuthenticatedUser actor,
            AdminBulkChangeRequest request, String ip) {
        Instant now = clock.instant();
        BulkChangeHandler<?> handler = validate(request, true, now);
        UUID batchId = UUID.randomUUID();
        // The summary is registered before any target's work, and filled in
        // after it: after-commit callbacks run in registration order, so no
        // target's callback, however it fails, can come before it.
        Map<String, Object> summary = new LinkedHashMap<>();
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.ADMIN_BULK_CHANGE, "bulk_change", batchId, summary, ip);
        // Every target's after-commit work is isolated from the others', so
        // one failed enqueue or push does not drop the rest.
        List<AdminBulkChangeApplyItem> items = AfterCommit.isolating(
                () -> apply(handler, actor, request, batchId, ip, now));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AdminBulkChangeResult result : AdminBulkChangeResult.values()) {
            counts.put(result.name(), 0);
        }
        for (AdminBulkChangeApplyItem item : items) {
            counts.merge(item.result().name(), 1, Integer::sum);
        }
        summary.put("batchId", batchId);
        summary.put("targetType", request.targetType().name());
        summary.put("kind", request.change().kind().name());
        summary.put("targets", items.size());
        summary.put("counts", counts);
        return new AdminBulkChangeApplyResponse(batchId, items);
    }

    private <T> List<AdminBulkChangePreviewItem> preview(BulkChangeHandler<T> handler,
            AuthenticatedUser actor, AdminBulkChangeRequest request, Instant now) {
        AdminBulkChangeSpec change = request.change();
        Map<UUID, T> targets = handler.load(request);
        List<AdminBulkChangePreviewItem> items = new ArrayList<>();
        for (UUID targetId : request.targetIds()) {
            T target = targets.get(targetId);
            AdminBulkChangeReason reach = target == null ? AdminBulkChangeReason.NOT_FOUND
                    : handler.accessRefusal(actor, target, change);
            if (reach != null) {
                // No fingerprint of a target the actor may not change: hashing
                // its values would hand out an oracle over what they are.
                items.add(new AdminBulkChangePreviewItem(targetId,
                        reach == AdminBulkChangeReason.NOT_FOUND ? null : handler.name(target),
                        false, reach, List.of(), BulkFingerprints.of(objectMapper, Map.of())));
                continue;
            }
            String fingerprint = BulkFingerprints.of(objectMapper,
                    handler.fingerprintValues(target, change));
            BulkChangeHandler.Judgement judgement = handler.judge(actor, target, change, now);
            items.add(new AdminBulkChangePreviewItem(targetId, handler.name(target),
                    judgement.reason() == null, judgement.reason(), judgement.fields(),
                    fingerprint));
        }
        return items;
    }

    private <T> List<AdminBulkChangeApplyItem> apply(BulkChangeHandler<T> handler,
            AuthenticatedUser actor, AdminBulkChangeRequest request, UUID batchId, String ip,
            Instant now) {
        AdminBulkChangeSpec change = request.change();
        Map<UUID, String> fingerprints = request.fingerprints();
        Map<UUID, T> targets = handler.load(request);
        List<UUID> order = request.targetIds().stream()
                .filter(targets::containsKey)
                .sorted(Comparator.comparingLong(id -> handler.orderKey(targets.get(id))))
                .toList();
        // Only what the actor may reach is judged or locked: a row outside
        // their scope is reported without ever being touched.
        List<UUID> reachable = order.stream()
                .filter(id -> handler.accessRefusal(actor, targets.get(id), change) == null)
                .toList();
        if (handler.bumpsGateway()) {
            // Spend the generation only when something will be written, and
            // before any row is locked: the generation row is the
            // serialization point every document write queues on, and taking
            // any other lock first would invert the order the single paths
            // use. The judgment itself holds no lock, which is what lets it
            // run here.
            boolean anyWrite = false;
            for (UUID targetId : reachable) {
                T target = targets.get(targetId);
                if (!fingerprints.get(targetId).equals(BulkFingerprints.of(objectMapper,
                        handler.fingerprintValues(target, change)))) {
                    continue;
                }
                if (handler.judge(actor, target, change, now).writes()) {
                    anyWrite = true;
                    break;
                }
            }
            if (anyWrite) {
                generations.bump();
            }
        }
        for (UUID targetId : reachable) {
            handler.lock(targets.get(targetId));
        }
        Map<UUID, AdminBulkChangeApplyItem> results = new LinkedHashMap<>();
        for (UUID targetId : order) {
            T target = targets.get(targetId);
            AdminBulkChangeReason reach = handler.accessRefusal(actor, target, change);
            if (reach != null) {
                results.put(targetId, new AdminBulkChangeApplyItem(targetId,
                        reach == AdminBulkChangeReason.NOT_FOUND ? null : handler.name(target),
                        AdminBulkChangeResult.SKIPPED, reach, List.of()));
                continue;
            }
            String name = handler.name(target);
            String fingerprint = BulkFingerprints.of(objectMapper,
                    handler.fingerprintValues(target, change));
            if (!fingerprint.equals(fingerprints.get(targetId))) {
                results.put(targetId, new AdminBulkChangeApplyItem(targetId, name,
                        AdminBulkChangeResult.STALE, null, List.of()));
                continue;
            }
            BulkChangeHandler.Judgement judgement = handler.judge(actor, target, change, now);
            if (judgement.reason() != null) {
                results.put(targetId, new AdminBulkChangeApplyItem(targetId, name,
                        AdminBulkChangeResult.SKIPPED, judgement.reason(), List.of()));
                continue;
            }
            if (!judgement.writes()) {
                results.put(targetId, new AdminBulkChangeApplyItem(targetId, name,
                        AdminBulkChangeResult.UNCHANGED, null, List.of()));
                continue;
            }
            AdminBulkChangeReason refused = handler.write(actor, target, judgement, change,
                    batchId, ip);
            results.put(targetId, refused == null
                    ? new AdminBulkChangeApplyItem(targetId, name, AdminBulkChangeResult.APPLIED,
                            null, judgement.fields())
                    : new AdminBulkChangeApplyItem(targetId, name, AdminBulkChangeResult.SKIPPED,
                            refused, List.of()));
        }
        List<AdminBulkChangeApplyItem> items = new ArrayList<>();
        for (UUID targetId : request.targetIds()) {
            AdminBulkChangeApplyItem item = results.get(targetId);
            items.add(item != null ? item : new AdminBulkChangeApplyItem(targetId, null,
                    AdminBulkChangeResult.SKIPPED, AdminBulkChangeReason.NOT_FOUND, List.of()));
        }
        return items;
    }

    /**
     * The checks that hold for the request as a whole, answered together as
     * one 422: the kind and target type agree, exactly the kind's member is
     * present, no id repeats, and (on apply) every target has a fingerprint.
     */
    private BulkChangeHandler<?> validate(AdminBulkChangeRequest request, boolean applying,
            Instant now) {
        List<FieldValidationError> errors = new ArrayList<>();
        AdminBulkChangeSpec change = request.change();
        AdminBulkChangeKind kind = change.kind();
        if (!kind.accepts(request.targetType())) {
            errors.add(new FieldValidationError("targetType",
                    "이 변경 종류는 " + request.targetType() + " 대상에 쓸 수 없습니다."));
        }
        Set<UUID> distinct = new HashSet<>(request.targetIds());
        if (distinct.size() != request.targetIds().size()) {
            errors.add(new FieldValidationError("targetIds", "같은 대상을 두 번 지정했습니다."));
        }
        checkMembers(change, errors);
        if (applying) {
            Map<UUID, String> fingerprints = request.fingerprints();
            if (fingerprints == null || !fingerprints.keySet().containsAll(distinct)
                    || fingerprints.values().stream().anyMatch(value -> value == null)) {
                errors.add(new FieldValidationError("fingerprints",
                        "모든 대상의 fingerprint를 미리보기에서 받은 대로 보내 주세요."));
            }
        }
        BulkChangeHandler<?> handler = handlers.get(kind);
        if (handler == null) {
            throw new IllegalStateException("no handler for bulk change kind " + kind);
        }
        if (errors.isEmpty()) {
            handler.validate(request, errors, now);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
        return handler;
    }

    private static void checkMembers(AdminBulkChangeSpec change, List<FieldValidationError> errors) {
        Map<AdminBulkChangeKind, Object> members = new EnumMap<>(AdminBulkChangeKind.class);
        members.put(AdminBulkChangeKind.LLM_KEY_LIMITS, change.llmKeyLimits());
        members.put(AdminBulkChangeKind.LLM_KEY_STATUS, change.llmKeyStatus());
        members.put(AdminBulkChangeKind.LLM_KEY_EXPIRY, change.llmKeyExpiry());
        members.put(AdminBulkChangeKind.VM_PERIOD, change.vmPeriod());
        members.put(AdminBulkChangeKind.VM_POWER, change.vmPower());
        members.put(AdminBulkChangeKind.VM_DELETION, change.vmDeletion());
        members.put(AdminBulkChangeKind.DOMAIN_RENEWAL, change.domainRenewal());
        members.put(AdminBulkChangeKind.DOMAIN_FORCE_RELEASE, change.domainForceRelease());
        members.put(AdminBulkChangeKind.DOMAIN_VERIFY, change.domainVerify());
        members.put(AdminBulkChangeKind.ACCESS, change.access());
        for (AdminBulkChangeKind kind : AdminBulkChangeKind.values()) {
            Object member = members.get(kind);
            String field = "change." + memberName(kind);
            if (kind == change.kind() && member == null) {
                errors.add(new FieldValidationError(field, "이 변경 종류의 내용을 채워 주세요."));
            } else if (kind != change.kind() && member != null) {
                errors.add(new FieldValidationError(field, "변경 종류와 다른 내용은 보낼 수 없습니다."));
            }
        }
    }

    static String memberName(AdminBulkChangeKind kind) {
        return switch (kind) {
            case LLM_KEY_LIMITS -> "llmKeyLimits";
            case LLM_KEY_STATUS -> "llmKeyStatus";
            case LLM_KEY_EXPIRY -> "llmKeyExpiry";
            case VM_PERIOD -> "vmPeriod";
            case VM_POWER -> "vmPower";
            case VM_DELETION -> "vmDeletion";
            case DOMAIN_RENEWAL -> "domainRenewal";
            case DOMAIN_FORCE_RELEASE -> "domainForceRelease";
            case DOMAIN_VERIFY -> "domainVerify";
            case ACCESS -> "access";
        };
    }
}
