package kr.ac.pusan.pickle.llm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CREDIT-axis model patterns: normalization, validation and the JSON form the
 * columns store.
 *
 * <p>One class holds all of it because the same kind of value is set by several
 * writers — approval, the administrator limits replacement and an account's
 * prefill default — and a rule that lives in three places is a rule that will
 * disagree with itself. The class is named for the value rather than for one of
 * its uses: the allow list and the deny list are the same syntax judged by the
 * same function, and only their meaning differs.
 *
 * <p><b>Empty means "this list places no constraint".</b> There is no null
 * state: every caller turns a missing value into an empty list here, so an
 * unconstrained axis has one spelling from the request body to the gateway
 * document. The two lists spell it identically and mean opposite things —
 * an empty allow list opens every model, an empty deny list closes none.
 *
 * <p><b>This validation is a courtesy, not the enforcement.</b> The gateway is
 * the only place that decides what a key may call; refusing a malformed pattern
 * here just tells the reviewer immediately instead of letting them save
 * something that would never match. The two sides therefore need not agree
 * perfectly — but they do agree today, and the reserved prefixes below are the
 * one piece of gateway knowledge duplicated here, so a change there wants a
 * look at this file.
 */
public final class CreditModelPatterns {

    private static final Logger log = LoggerFactory.getLogger(CreditModelPatterns.class);

    /** The empty list in its stored form. */
    public static final String EMPTY_JSON = "[]";

    /** Pattern syntax shared by the write paths and the database constraint. */
    private static final Pattern PATTERN = Pattern.compile(
            "^(~?[a-z0-9][a-z0-9._:-]*(/([a-z0-9][a-z0-9._:-]*\\*?"
                    + "|\\*[a-z0-9._:-]*[a-z0-9]|\\*))?"
                    + "|\\*/([a-z0-9][a-z0-9._:-]*\\*?|\\*[a-z0-9._:-]*[a-z0-9]|\\*))$");

    public static final String ALLOW_PATTERN_DESCRIPTION =
            " 항목은 부호 없는 모델 패턴입니다. 공급자는 정확한 이름 또는 *만 사용할 수 "
                    + "있습니다(예: openai/*, */*-pro). 공급자 *는 ~별칭도 포함하고, 특정 "
                    + "공급자의 허용은 ~별칭을 별도로 적어야 합니다. 정확한 모델명은 :batch 같은 "
                    + "변형도 포함합니다. 목록별 최대 50개, 항목별 200바이트입니다.";

    public static final String DENY_PATTERN_DESCRIPTION =
            " 항목은 허용 목록과 같은 부호 없는 모델 패턴입니다. 공급자는 정확한 이름 또는 "
                    + "*만 사용할 수 있습니다(예: openai/*, */*-pro). 차단은 ~를 제거한 이름도 "
                    + "검사하며, 별칭의 실제 대상 모델은 추적하지 않습니다. 정확한 모델명은 "
                    + ":batch 같은 변형도 포함합니다. 목록별 최대 50개, 항목별 200바이트입니다.";

    /**
     * Self-serving model prefixes. A name starting with one of these is served
     * from our own hardware on the TOKEN axis, so it is never a commercial name
     * and listing it here would read as opening something this list cannot
     * open. Retired prefixes stay in the set for the same reason the gateway
     * keeps them: an old name typed by mistake should be refused, not billed.
     */
    private static final List<String> RESERVED_PREFIXES = List.of("pickle-", "pnu-");

    private static final int MAX_ENTRIES = 50;
    private static final int MAX_ENTRY_BYTES = 200;

    private CreditModelPatterns() {
    }

    /**
     * Normalizes what a caller sent and reports every problem it finds against
     * {@code field}. Returns the list to store; an invalid entry leaves an
     * error behind and is dropped, so callers must check {@code errors} before
     * using the result.
     *
     * <p>Normalization is lower-casing, trimming, and removing blanks and
     * duplicates while keeping the order somebody typed. Lower-casing is not
     * cosmetic: the gateway compares against a lower-cased model name, so a
     * pattern stored with capitals would silently match nothing.
     */
    public static List<String> normalize(@Nullable List<String> raw, String field,
            List<FieldValidationError> errors) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> kept = new LinkedHashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String entry = raw.get(i);
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String value = entry.trim().toLowerCase(Locale.ROOT);
            String at = field + "[" + i + "]";
            if (value.getBytes(StandardCharsets.UTF_8).length > MAX_ENTRY_BYTES) {
                errors.add(new FieldValidationError(at, "모델 이름이 너무 깁니다."));
                continue;
            }
            if ("*".equals(value)) {
                // Said without naming a direction: this method serves both the
                // allow list and the deny list, and "leave it empty to allow
                // everything" is false advice on the second one.
                errors.add(new FieldValidationError(at,
                        "'*' 하나만 적을 수는 없습니다. 목록을 비우면 이 목록은 아무것도 제한하지 "
                                + "않고, 전체 공급자는 '*/*', 한 공급자는 'openai/*'처럼 적습니다."));
                continue;
            }
            if (isReserved(value)) {
                errors.add(new FieldValidationError(at,
                        "자체 서빙 모델은 이 목록의 대상이 아닙니다. 유료 모델 이름을 적어 주세요."));
                continue;
            }
            if (!PATTERN.matcher(value).matches()) {
                errors.add(new FieldValidationError(at,
                        "모델 이름(예: openai/gpt-4o-mini), 공급자 전체(예: openai/*), 또는 "
                                + "모델 자리에 와일드카드를 하나 포함한 패턴(예: openai/gpt-5-*, "
                                + "*/*-pro) 형식이어야 합니다. 공급자는 정확한 이름 또는 '*'만 사용할 수 있습니다."));
                continue;
            }
            kept.add(value);
        }
        // Unreachable through the request DTOs, which carry @Size(max = 50) on
        // the raw list, and deduplication only shrinks it. Kept because this
        // method is the one place that decides what a stored list may be, and a
        // future caller that is not a validated DTO would otherwise have no
        // bound at all.
        if (kept.size() > MAX_ENTRIES) {
            errors.add(new FieldValidationError(field,
                    "모델은 최대 " + MAX_ENTRIES + "개까지 허용할 수 있습니다."));
            return List.of();
        }
        return List.copyOf(kept);
    }

    /**
     * Whether the name belongs to a self-serving prefix.
     *
     * <p>A leading tilde is stripped before comparing. Without that, widening
     * the pattern to admit floating aliases would also admit
     * {@code ~pickle-general} — a name this list is not allowed to hold,
     * slipping past the guard on one character.
     */
    public static boolean isReserved(String name) {
        // Lower-cased here rather than trusted from the caller. normalize()
        // already does it, but this method is public and a future caller that
        // skips it would get a guard that a capital letter walks past. The
        // gateway's counterpart lowers defensively for the same reason.
        String bare = name.toLowerCase(Locale.ROOT);
        bare = bare.startsWith("~") ? bare.substring(1) : bare;
        for (String prefix : RESERVED_PREFIXES) {
            if (bare.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Matches the gateway's model syntax. A whole-provider star includes aliases;
     * concrete providers keep their leading tilde significant. Exact names and
     * leading-star patterns also match the model before its variant suffix.
     * The trailing-star separator rule remains a comparison against the full name.
     */
    public static boolean matches(String pattern, String modelName) {
        String p = pattern.toLowerCase(Locale.ROOT);
        String n = modelName.toLowerCase(Locale.ROOT);
        if (p.isEmpty() || "*".equals(p)) {
            return false;
        }
        int slash = p.indexOf('/');
        if (slash < 0) {
            return p.equals(n) || p.equals(withoutVariant(n));
        }
        String vendor = p.substring(0, slash);
        String rest;
        if ("*".equals(vendor)) {
            int nameSlash = n.indexOf('/');
            if (nameSlash <= 0) {
                return false;
            }
            rest = n.substring(nameSlash + 1);
        } else {
            String prefix = vendor + "/";
            if (!n.startsWith(prefix)) {
                return false;
            }
            rest = n.substring(prefix.length());
        }
        if (rest.isEmpty()) {
            return false;
        }
        String seg = p.substring(slash + 1);
        if ("*".equals(seg)) {
            return true;
        }
        if (seg.startsWith("*")) {
            String tail = seg.substring(1);
            int colon = rest.indexOf(':');
            String base = colon < 0 ? rest : rest.substring(0, colon);
            return rest.endsWith(tail) || base.endsWith(tail);
        }
        if (seg.endsWith("*")) {
            String stem = seg.substring(0, seg.length() - 1);
            if (rest.startsWith(stem)) {
                return true;
            }
            // The separator rule: a stem ending in one of these also names the
            // model without it, so "openai/gpt-5-*" reaches "openai/gpt-5".
            // Prefix matching alone cannot, because the two are not prefixes.
            return !stem.isEmpty()
                    && "-.:".indexOf(stem.charAt(stem.length() - 1)) >= 0
                    && rest.equals(stem.substring(0, stem.length() - 1));
        }
        return rest.equals(seg) || withoutVariant(rest).equals(seg);
    }

    /** Denials also inspect the alias name with all leading tildes removed. */
    public static boolean matchesDenied(String pattern, String modelName) {
        return matches(pattern, modelName) || matches(pattern, withoutAlias(modelName));
    }

    /** A paid-model restriction excludes the whole router namespace. */
    public static boolean allows(List<String> allowed, List<String> denied, String modelName) {
        String name = modelName.trim().toLowerCase(Locale.ROOT);
        if ((!allowed.isEmpty() || !denied.isEmpty()) && isRouter(name)) {
            return false;
        }
        return (allowed.isEmpty() || allowed.stream().anyMatch(pattern -> matches(pattern, name)))
                && denied.stream().noneMatch(pattern -> matchesDenied(pattern, name));
    }

    public static boolean isRouter(String modelName) {
        return withoutAlias(modelName.trim().toLowerCase(Locale.ROOT)).startsWith("openrouter/");
    }

    private static String withoutAlias(String name) {
        int start = 0;
        while (start < name.length() && name.charAt(start) == '~') {
            start++;
        }
        return name.substring(start);
    }

    private static String withoutVariant(String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(0, colon);
    }

    /** The stored form. Never null, so the column's not-null holds trivially. */
    public static String toJson(ObjectMapper mapper, List<String> models) {
        if (models.isEmpty()) {
            return EMPTY_JSON;
        }
        return mapper.writeValueAsString(models);
    }

    /**
     * Reads a stored value back. A row that cannot be parsed reads as empty
     * rather than throwing: the column is CHECK-constrained, so this can only
     * happen to a value written outside the application, and failing a whole
     * key list over one unreadable row would take the service down for a
     * problem that belongs to one key.
     *
     * <p><b>Empty is not the safe reading on the deny side.</b> An unreadable
     * allow list read as empty opens every model; an unreadable deny list read
     * as empty opens exactly the models somebody refused.
     *
     * <p><b>Nothing downstream catches that, and it is worth being exact about
     * why.</b> The gateway does drop a key whose document it cannot parse, but
     * it never sees this one: the sync document is built through this same
     * method, so a malformed column is already an empty array by the time it is
     * serialized. The gateway is handed a well-formed empty deny list and goes
     * on serving the key. So the two sides do not contradict each other — they
     * agree on "nothing is blocked", which is the wrong answer reached quietly
     * from both ends, and the refusal an approver recorded is simply gone.
     * {@code LlmGatewayEndpointTest} pins that behaviour rather than leaving it
     * to be guessed at.
     *
     * <p>The leniency stays anyway, because the reachable states do not justify
     * the alternative: the column is not null and CHECK-constrained to a JSON
     * array, so only a write from outside the application produces one, and
     * throwing here would take down every screen that lists keys — including the
     * one an approver would use to repair the row. What the situation gets
     * instead is the WARN below, which is why {@code source} is a parameter:
     * this arrives as "why does this key behave oddly", and a warning without a
     * key in it cannot answer that.
     */
    public static List<String> fromJson(ObjectMapper mapper, @Nullable String stored,
            String source) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = mapper.readTree(stored);
            if (!node.isArray()) {
                log.warn("stored credit model list for {} is not a JSON array; reading it as "
                        + "empty, so every screen and the gateway document alike report no "
                        + "restriction and a recorded refusal stops being applied", source);
                return List.of();
            }
            List<String> models = new ArrayList<>(node.size());
            node.forEach(item -> {
                if (item.isString() && !item.asString().isBlank()) {
                    models.add(item.asString());
                }
            });
            if (models.size() != node.size()) {
                log.warn("dropped {} unreadable entr(ies) from the stored credit model list for "
                        + "{}; on a deny list that silently stops blocking what was refused",
                        node.size() - models.size(), source);
            }
            return List.copyOf(models);
        } catch (JacksonException e) {
            log.warn("could not parse the stored credit model list for {}; reading it as empty, "
                    + "so every screen and the gateway document alike report no restriction and "
                    + "a recorded refusal stops being applied", source, e);
            return List.of();
        }
    }
}
