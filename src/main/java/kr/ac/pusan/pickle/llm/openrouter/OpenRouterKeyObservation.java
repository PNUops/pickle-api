package kr.ac.pusan.pickle.llm.openrouter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Exact cap/remaining facts from one response, separate from the lifetime ledger. */
public record OpenRouterKeyObservation(boolean valid, boolean limitReported,
        @Nullable BigDecimal limit, boolean resetReported, @Nullable String limitReset,
        @Nullable Boolean includeByokInLimit, @Nullable Boolean disabled,
        @Nullable BigDecimal limitRemaining, Instant requestStartedAt, Instant requestEndedAt) {

    public static OpenRouterKeyObservation from(@Nullable JsonNode raw, Instant start, Instant end) {
        boolean valid = raw != null && raw.isObject() && !end.isBefore(start);
        if (raw == null) {
            return new OpenRouterKeyObservation(false, false, null, false, null, null, null,
                    null, start, end);
        }
        for (String field : List.of("limit", "limit_remaining")) {
            JsonNode value = raw.path(field);
            if (!value.isMissingNode() && !value.isNull()
                    && (!value.isNumber() || !bounded(value.decimalValue())
                    || !"limit_remaining".equals(field) && value.decimalValue().signum() < 0)) { valid = false; }
        }
        for (String field : List.of("disabled", "include_byok_in_limit")) {
            if (raw.has(field) && !raw.path(field).isBoolean()) { valid = false; }
        }
        JsonNode reset = raw.path("limit_reset");
        if (!reset.isMissingNode() && !reset.isNull() && (!reset.isString()
                || !List.of("daily", "weekly", "monthly").contains(reset.asString()))) { valid = false; }
        return new OpenRouterKeyObservation(valid, raw.has("limit"), number(raw, "limit"),
                raw.has("limit_reset"), reset.isString() ? reset.asString() : null,
                bool(raw, "include_byok_in_limit"), bool(raw, "disabled"),
                number(raw, "limit_remaining"), start, end);
    }

    private static boolean bounded(BigDecimal value) { return value.precision() <= 128 && Math.abs((long) value.scale()) <= 128; }
    private static @Nullable BigDecimal number(JsonNode raw, String field) {
        return raw.path(field).isNumber() && bounded(raw.path(field).decimalValue()) ? raw.path(field).decimalValue() : null;
    }
    private static @Nullable Boolean bool(JsonNode raw, String field) {
        return raw.path(field).isBoolean() ? raw.path(field).booleanValue() : null;
    }
}
