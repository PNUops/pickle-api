package kr.ac.pusan.pickle.admin.bulk;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.AdminLlmKeyService;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeFieldDiff;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeKind;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeReason;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeRequest;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeSpec;
import kr.ac.pusan.pickle.admin.dto.AdminBulkListChange;
import kr.ac.pusan.pickle.admin.dto.AdminBulkLlmKeyLimitsChange;
import kr.ac.pusan.pickle.admin.dto.AdminBulkLlmKeyStatusAction;
import kr.ac.pusan.pickle.admin.dto.AdminBulkLlmKeyStatusChange;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.llm.CreditModelPatterns;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyRepository;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import kr.ac.pusan.pickle.llm.LlmKeyLimitRules;
import kr.ac.pusan.pickle.llm.PassthroughEndpoints;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccount;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The three LLM key kinds of a bulk change: limits, status and expiry.
 *
 * <p>Reach is the administrator's single path's: the org tier changes keys of
 * the institutions it operates and is answered NOT_FOUND elsewhere. Revoking
 * is narrower, as on the holder's path: a system administrator, or an
 * organisation administrator of the key's institution; anyone else who can
 * see the key is told FORBIDDEN.
 *
 * <p>Every write here reaches the gateway document, so the family reports
 * that it spends a generation, and the lock it takes is the row lock, taken
 * after that generation and in id order.
 */
@Component
class LlmKeyBulkChanges extends BulkChangeHandler<LlmApiKey> {

    private static final String LIMITS_FIELD = "change.llmKeyLimits.";

    private final LlmApiKeyRepository keyRepository;
    private final AdminLlmKeyService keyService;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    LlmKeyBulkChanges(LlmApiKeyRepository keyRepository, AdminLlmKeyService keyService,
            EntityManager entityManager, ObjectMapper objectMapper, Clock clock) {
        this.keyRepository = keyRepository;
        this.keyService = keyService;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** What a limits judgment settled on, carried to the write untouched. */
    private record LimitsPlan(LlmKeyLimitRules.Normalized current, LlmKeyLimitRules.Normalized next,
            @Nullable OpenRouterAccount account, boolean bindingChanged) {
    }

    @Override
    Set<AdminBulkChangeKind> kinds() {
        return Set.of(AdminBulkChangeKind.LLM_KEY_LIMITS, AdminBulkChangeKind.LLM_KEY_STATUS,
                AdminBulkChangeKind.LLM_KEY_EXPIRY);
    }

    @Override
    void validate(AdminBulkChangeRequest request, List<FieldValidationError> errors,
            Instant now) {
        AdminBulkChangeSpec change = request.change();
        switch (change.kind()) {
            case LLM_KEY_LIMITS -> {
                AdminBulkLlmKeyLimitsChange limits = change.llmKeyLimits();
                if (limits.isEmpty()) {
                    errors.add(new FieldValidationError("change.llmKeyLimits",
                            "바꿀 한도를 하나 이상 지정해 주세요."));
                    return;
                }
                if (limits.isCreditLimitSet() && limits.getCreditLimit() == null) {
                    errors.add(new FieldValidationError(LIMITS_FIELD + "creditLimit",
                            "금액 한도는 null일 수 없습니다. 유료 모델을 닫으려면 0을 보내 주세요."));
                }
                // The values of a list operation are normalized here, once for
                // the whole request, so a misspelt capability is a 422 rather
                // than two hundred SKIPPED rows.
                List<FieldValidationError> listErrors = new ArrayList<>();
                if (limits.getCreditAllowedModels() != null) {
                    CreditModelPatterns.normalize(limits.getCreditAllowedModels().values(),
                            LIMITS_FIELD + "creditAllowedModels.values", listErrors);
                }
                if (limits.getCreditDeniedModels() != null) {
                    CreditModelPatterns.normalize(limits.getCreditDeniedModels().values(),
                            LIMITS_FIELD + "creditDeniedModels.values", listErrors);
                }
                if (limits.getPassthroughEndpoints() != null) {
                    PassthroughEndpoints.normalize(limits.getPassthroughEndpoints().values(),
                            LIMITS_FIELD + "passthroughEndpoints.values", listErrors);
                }
                errors.addAll(listErrors);
            }
            case LLM_KEY_STATUS -> {
                AdminBulkLlmKeyStatusChange status = change.llmKeyStatus();
                if (status.action() == AdminBulkLlmKeyStatusAction.SUSPEND
                        && (status.reason() == null || status.reason().isBlank())) {
                    errors.add(new FieldValidationError("change.llmKeyStatus.reason",
                            "정지 사유를 입력해 주세요."));
                }
            }
            case LLM_KEY_EXPIRY -> {
                try {
                    AdminLlmKeyService.expiresAtFor(change.llmKeyExpiry().endDate(), clock);
                } catch (kr.ac.pusan.pickle.common.error.ApiException tooEarly) {
                    for (FieldValidationError error : tooEarly.getErrors()) {
                        errors.add(new FieldValidationError("change.llmKeyExpiry."
                                + error.field(), error.message()));
                    }
                }
            }
            default -> throw new IllegalStateException("not an LLM key kind: " + change.kind());
        }
    }

    @Override
    Map<UUID, LlmApiKey> load(AdminBulkChangeRequest request) {
        Map<UUID, LlmApiKey> keys = new LinkedHashMap<>();
        for (UUID id : request.targetIds()) {
            keyRepository.findByPublicId(id).ifPresent(key -> keys.put(id, key));
        }
        return keys;
    }

    @Override
    long orderKey(LlmApiKey key) {
        return key.getId();
    }

    @Override
    String name(LlmApiKey key) {
        return key.getName();
    }

    @Override
    @Nullable AdminBulkChangeReason accessRefusal(AuthenticatedUser actor, LlmApiKey key,
            AdminBulkChangeSpec change) {
        if (actor.role().isOrgTier() && !actor.operates(key.getOrgId())) {
            return AdminBulkChangeReason.NOT_FOUND;
        }
        if (change.kind() == AdminBulkChangeKind.LLM_KEY_STATUS
                && change.llmKeyStatus().action() == AdminBulkLlmKeyStatusAction.REVOKE) {
            boolean mayRevoke = actor.role() == UserRole.SYS_ADMIN
                    || (actor.role() == UserRole.ORG_ADMIN && actor.administers(key.getOrgId()));
            return mayRevoke ? null : AdminBulkChangeReason.FORBIDDEN;
        }
        return null;
    }

    @Override
    Map<String, Object> fingerprintValues(LlmApiKey key, AdminBulkChangeSpec change) {
        Map<String, Object> values = new LinkedHashMap<>();
        // Every kind judges on the status, and expiry is part of it, so both
        // are in every fingerprint: a key that lapsed or was revoked between
        // the load and the lock reads as changed, not as writable.
        values.put("status", key.getStatus().name());
        values.put("expiresAt", BulkFingerprints.plain(key.getExpiresAt()));
        if (change.kind() == AdminBulkChangeKind.LLM_KEY_LIMITS) {
            keyService.currentLimits(key).asAuditFields()
                    .forEach((field, value) -> values.put(field, BulkFingerprints.plain(value)));
        }
        return values;
    }

    @Override
    Judgement judge(AuthenticatedUser actor, LlmApiKey key, AdminBulkChangeSpec change,
            Instant now) {
        return switch (change.kind()) {
            case LLM_KEY_LIMITS -> judgeLimits(actor, key, change.llmKeyLimits(), now);
            case LLM_KEY_STATUS -> judgeStatus(key, change.llmKeyStatus(), now);
            case LLM_KEY_EXPIRY -> judgeExpiry(key, change.llmKeyExpiry().endDate(), now);
            default -> throw new IllegalStateException("not an LLM key kind: " + change.kind());
        };
    }

    private Judgement judgeLimits(AuthenticatedUser actor, LlmApiKey key,
            AdminBulkLlmKeyLimitsChange limits, Instant now) {
        if (!AdminLlmKeyService.isMutable(key.effectiveStatus(now))) {
            return Judgement.refused(AdminBulkChangeReason.INVALID_STATE);
        }
        LlmKeyLimitRules.Normalized current = keyService.currentLimits(key);
        // Merge onto what the key has, then judge the merged result by the
        // rules the single path applies to a whole form. The rules see the
        // same nine values either way; only where they came from differs.
        LlmKeyLimitRules.Outcome outcome = LlmKeyLimitRules.check(new LlmKeyLimitRules.Limits(
                limits.isRpmSet() ? limits.getRpm() : current.rpm(),
                limits.isTpmSet() ? limits.getTpm() : current.tpm(),
                limits.isConcurrencySet() ? limits.getConcurrency() : current.concurrency(),
                limits.isDailyTokensSet() ? limits.getDailyTokens() : current.dailyTokens(),
                limits.isCreditLimitSet() ? limits.getCreditLimit() : current.creditLimit(),
                limits.isCreditLimitResetSet() ? limits.getCreditLimitReset()
                        : current.creditLimitReset(),
                merge(limits.getCreditAllowedModels(), current.creditAllowedModels(), true),
                merge(limits.getCreditDeniedModels(), current.creditDeniedModels(), true),
                merge(limits.getPassthroughEndpoints(), current.passthroughEndpoints(), false)),
                limits.getCreditAllowedModels() != null, limits.getCreditDeniedModels() != null,
                limits.getPassthroughEndpoints() != null);
        if (!outcome.valid()) {
            return Judgement.refused(AdminBulkChangeReason.VALIDATION);
        }
        LlmKeyLimitRules.Normalized next = outcome.normalized();
        List<AdminBulkChangeFieldDiff> fields = new ArrayList<>();
        Map<String, Object> before = current.asAuditFields();
        Map<String, Object> after = next.asAuditFields();
        for (String field : before.keySet()) {
            Object oldValue = before.get(field);
            Object newValue = after.get(field);
            boolean same = oldValue instanceof java.math.BigDecimal a
                    && newValue instanceof java.math.BigDecimal b
                    ? a.compareTo(b) == 0 : Objects.equals(oldValue, newValue);
            if (!same) {
                fields.add(diff(objectMapper, field, oldValue, newValue));
            }
        }
        if (fields.isEmpty()) {
            return Judgement.unchanged();
        }
        boolean moneyChanged = AdminLlmKeyService.moneyChanged(current, next);
        if (actor.role() == UserRole.SYS_MANAGER && moneyChanged) {
            return Judgement.refused(AdminBulkChangeReason.FORBIDDEN);
        }
        AdminLlmKeyService.AccountResolution account =
                keyService.resolveAccountQuietly(key, next.creditLimit());
        if (!account.accepted()) {
            return Judgement.refused(AdminBulkChangeReason.VALIDATION);
        }
        boolean bindingChanged = key.getOpenrouterAccountId() == null && account.account() != null;
        return Judgement.change(fields, new LimitsPlan(current, next, account.account(),
                bindingChanged));
    }

    /**
     * A list after the operation, if one was sent. The operation's values are
     * normalized first so ADD does not repeat what the key already lists under
     * another spelling and REMOVE finds what it names; the request-level check
     * already refused anything that would not normalize.
     */
    private static List<String> merge(@Nullable AdminBulkListChange operation,
            List<String> current, boolean modelPatterns) {
        if (operation == null) {
            return current;
        }
        List<FieldValidationError> none = new ArrayList<>();
        List<String> values = modelPatterns
                ? CreditModelPatterns.normalize(operation.values(), "values", none)
                : PassthroughEndpoints.normalize(operation.values(), "values", none);
        return new AdminBulkListChange(operation.op(), values).applyTo(current);
    }

    private Judgement judgeStatus(LlmApiKey key, AdminBulkLlmKeyStatusChange status, Instant now) {
        LlmApiKeyStatus effective = key.effectiveStatus(now);
        LlmApiKeyStatus target = switch (status.action()) {
            case SUSPEND -> LlmApiKeyStatus.SUSPENDED;
            case RESUME -> LlmApiKeyStatus.ACTIVE;
            case REVOKE -> LlmApiKeyStatus.REVOKED;
        };
        if (effective == target) {
            return Judgement.unchanged();
        }
        boolean allowed = switch (status.action()) {
            case SUSPEND -> effective == LlmApiKeyStatus.ACTIVE;
            case RESUME -> effective == LlmApiKeyStatus.SUSPENDED;
            case REVOKE -> true;
        };
        if (!allowed) {
            return Judgement.refused(AdminBulkChangeReason.INVALID_STATE);
        }
        return Judgement.change(List.of(diff(objectMapper, "status", effective, target)), null);
    }

    private Judgement judgeExpiry(LlmApiKey key, java.time.LocalDate endDate, Instant now) {
        if (key.getStatus() == LlmApiKeyStatus.REVOKED) {
            return Judgement.refused(AdminBulkChangeReason.INVALID_STATE);
        }
        Instant next = AdminLlmKeyService.expiresAtFor(endDate, clock);
        if (AdminLlmKeyService.extendsProvisionedKey(key, next)) {
            // A key with an OpenRouter half may only be shortened: the vendor
            // fixes its expiry at creation, so extending ours alone would drift.
            return Judgement.refused(AdminBulkChangeReason.INELIGIBLE);
        }
        if (next.equals(key.getExpiresAt())) {
            return Judgement.unchanged();
        }
        List<AdminBulkChangeFieldDiff> fields = new ArrayList<>();
        fields.add(diff(objectMapper, "expiresAt", key.getExpiresAt(), next));
        LlmApiKeyStatus before = key.effectiveStatus(now);
        LlmApiKeyStatus after = key.getStatus() == LlmApiKeyStatus.EXPIRED
                ? (key.isIssued() ? LlmApiKeyStatus.ACTIVE : LlmApiKeyStatus.PENDING)
                : key.getStatus();
        if (before != after) {
            fields.add(diff(objectMapper, "status", before, after));
        }
        return Judgement.change(fields, next);
    }

    @Override
    void lock(LlmApiKey key) {
        // Lock and re-read in one statement: the judgment that follows has to
        // see the row as it is under the lock, not as it was loaded before the
        // generation was taken.
        entityManager.refresh(key, LockModeType.PESSIMISTIC_WRITE);
    }

    @Override
    boolean bumpsGateway() {
        return true;
    }

    @Override
    @Nullable AdminBulkChangeReason write(AuthenticatedUser actor, LlmApiKey key,
            Judgement judgement, AdminBulkChangeSpec change, UUID batchId, String ip) {
        switch (change.kind()) {
            case LLM_KEY_LIMITS -> {
                LimitsPlan plan = (LimitsPlan) judgement.plan();
                OpenRouterAccount account = plan.account();
                if (plan.bindingChanged()) {
                    // The judgment read the account without a lock; take it
                    // now, after the generation and this key's row, which is
                    // the order the single paths lock in.
                    account = keyService.lockAccount(account);
                    if (account == null) {
                        return AdminBulkChangeReason.VALIDATION;
                    }
                }
                keyService.writeLimits(actor, key, plan.current(), plan.next(), account,
                        plan.bindingChanged(), batchId, ip);
            }
            case LLM_KEY_STATUS -> {
                switch (change.llmKeyStatus().action()) {
                    case SUSPEND -> keyService.writeSuspend(actor, key,
                            change.llmKeyStatus().reason(), batchId, ip);
                    case RESUME -> keyService.writeResume(actor, key, batchId, ip);
                    case REVOKE -> keyService.writeRevoke(actor, key, batchId, ip);
                }
            }
            case LLM_KEY_EXPIRY -> keyService.writeExpiry(actor, key, (Instant) judgement.plan(),
                    batchId, ip);
            default -> throw new IllegalStateException("not an LLM key kind: " + change.kind());
        }
        return null;
    }
}
