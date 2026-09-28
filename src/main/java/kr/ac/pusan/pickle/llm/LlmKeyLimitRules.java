package kr.ac.pusan.pickle.llm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import org.jspecify.annotations.Nullable;

/**
 * The rules the nine limits of an LLM key must satisfy together, in one place.
 *
 * <p>The administrator's single-key replacement and the bulk change both land
 * on this: the first with the nine values a form sent, the second with the
 * values it merged onto a key's current ones. Two copies of "a reset window
 * needs money behind it" would be two policies within a release, and the bulk
 * path is exactly the one where a rule copied slightly wrong would be applied
 * to two hundred keys at once.
 *
 * <p>Each rule refuses in the order the single path always has, and the first
 * failing group is the whole answer, so the field an error names does not
 * depend on which path asked.
 */
public final class LlmKeyLimitRules {

    private LlmKeyLimitRules() {
    }

    /** The nine values as they arrived: lists raw, nothing normalized yet. */
    public record Limits(@Nullable Integer rpm, @Nullable Integer tpm,
            @Nullable Integer concurrency, @Nullable Long dailyTokens,
            @Nullable BigDecimal creditLimit, @Nullable CreditLimitReset creditLimitReset,
            @Nullable List<String> creditAllowedModels, @Nullable List<String> creditDeniedModels,
            @Nullable List<String> passthroughEndpoints) {
    }

    /**
     * The nine values once the rules passed: the lists normalized and the money
     * limit known to be present.
     */
    public record Normalized(@Nullable Integer rpm, @Nullable Integer tpm,
            @Nullable Integer concurrency, @Nullable Long dailyTokens, BigDecimal creditLimit,
            @Nullable CreditLimitReset creditLimitReset, List<String> creditAllowedModels,
            List<String> creditDeniedModels, List<String> passthroughEndpoints) {

        /** The values as an audit record names them, in the contract's order. */
        public Map<String, Object> asAuditFields() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("rpm", rpm);
            fields.put("tpm", tpm);
            fields.put("concurrency", concurrency);
            fields.put("dailyTokens", dailyTokens);
            fields.put("creditLimit", creditLimit);
            fields.put("creditLimitReset", creditLimitReset);
            fields.put("creditAllowedModels", creditAllowedModels);
            fields.put("creditDeniedModels", creditDeniedModels);
            fields.put("passthroughEndpoints", passthroughEndpoints);
            return fields;
        }
    }

    /** Either the normalized values or the errors that stopped them. */
    public record Outcome(@Nullable Normalized normalized, List<FieldValidationError> errors) {

        public boolean valid() {
            return normalized != null;
        }

        private static Outcome refused(String field, String message) {
            return new Outcome(null, List.of(new FieldValidationError(field, message)));
        }
    }

    public static Outcome check(Limits limits) {
        return check(limits, true, true, true);
    }

    private static List<String> stored(@Nullable List<String> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    /**
     * The same rules with a list left as it is stored where the change did not
     * touch it. A stored list was normalized when it was written, and
     * re-reading it through today's normalizer would let a pattern the rules
     * have since tightened turn an rpm-only change into a refusal. The
     * cross-field rules still see every list, untouched ones included.
     */
    public static Outcome check(Limits limits, boolean normalizeAllowed, boolean normalizeDenied,
            boolean normalizePassthrough) {
        if (limits.creditLimit() == null) {
            return Outcome.refused("creditLimit",
                    "금액 한도는 null일 수 없습니다. 유료 모델을 닫으려면 0을 보내 주세요.");
        }
        if (limits.tpm() != null && limits.rpm() != null && limits.tpm() < limits.rpm()) {
            return Outcome.refused("tpm", "분당 토큰 수는 분당 요청 수보다 작을 수 없습니다.");
        }
        if (limits.creditLimitReset() != null && limits.creditLimit().signum() <= 0) {
            return Outcome.refused("creditLimit", "리셋 창을 두려면 0보다 큰 금액 한도가 필요합니다.");
        }
        List<FieldValidationError> listErrors = new ArrayList<>();
        List<String> allowedModels = normalizeAllowed
                ? CreditModelPatterns.normalize(limits.creditAllowedModels(),
                        "creditAllowedModels", listErrors)
                : stored(limits.creditAllowedModels());
        List<String> deniedModels = normalizeDenied
                ? CreditModelPatterns.normalize(limits.creditDeniedModels(),
                        "creditDeniedModels", listErrors)
                : stored(limits.creditDeniedModels());
        List<String> passthroughEndpoints = normalizePassthrough
                ? PassthroughEndpoints.normalize(limits.passthroughEndpoints(),
                        "passthroughEndpoints", listErrors)
                : stored(limits.passthroughEndpoints());
        if (!listErrors.isEmpty()) {
            return new Outcome(null, List.copyOf(listErrors));
        }
        if (!allowedModels.isEmpty() && limits.creditLimit().signum() <= 0) {
            return Outcome.refused("creditLimit", "모델 허용 목록을 두려면 0보다 큰 금액 한도가 필요합니다.");
        }
        // No matching rule for the deny list, and the omission is the decision.
        // An allow list with no money behind it restricts nothing, so it reads
        // as a form the reviewer misfilled. A deny list at an amount of zero
        // still says something true, and says it about tomorrow, when somebody
        // funds the key without reopening this screen.
        return new Outcome(new Normalized(limits.rpm(), limits.tpm(), limits.concurrency(),
                limits.dailyTokens(), limits.creditLimit(), limits.creditLimitReset(),
                allowedModels, deniedModels, passthroughEndpoints), List.of());
    }
}
