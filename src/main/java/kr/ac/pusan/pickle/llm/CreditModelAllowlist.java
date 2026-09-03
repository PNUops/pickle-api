package kr.ac.pusan.pickle.llm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The CREDIT-axis model allow list: normalization, validation and the JSON form
 * the three columns store.
 *
 * <p>One class holds all of it because three writers set the same kind of value
 * — approval, the administrator limits replacement and an account's prefill
 * default — and a rule that lives in three places is a rule that will disagree
 * with itself.
 *
 * <p><b>Empty means unrestricted.</b> There is no null state: every caller
 * turns a missing value into an empty list here, so "no restriction" has one
 * spelling from the request body to the gateway document.
 *
 * <p><b>This validation is a courtesy, not the enforcement.</b> The gateway is
 * the only place that decides what a key may call; refusing a malformed pattern
 * here just tells the reviewer immediately instead of letting them save
 * something that would never match. The two sides therefore need not agree
 * perfectly — but they do agree today, and the reserved prefixes below are the
 * one piece of gateway knowledge duplicated here, so a change there wants a
 * look at this file.
 */
public final class CreditModelAllowlist {

    /** The empty list in its stored form. */
    public static final String EMPTY_JSON = "[]";

    /**
     * Matches the DB CHECK installed by V102 and widened by V106 — a model
     * name, or a vendor prefix, either one optionally carrying the vendor's
     * leading tilde.
     *
     * <p>The tilde admits floating aliases like {@code
     * ~anthropic/claude-sonnet-latest}, which always resolve to the newest
     * model of a family. They route through passthrough already, so a fence
     * that could not spell them left a restricted key narrower than an
     * unrestricted one, and "pin this course to the latest Sonnet" could not
     * be expressed at all. {@code ~anthropic/*} and {@code anthropic/*} stay
     * separate prefixes: an alias points at a model that changes underneath
     * it, so opening a vendor must not admit a moving target nobody chose.
     */
    private static final Pattern PATTERN =
            Pattern.compile("^~?[a-z0-9][a-z0-9._:-]*(/([a-z0-9][a-z0-9._:-]*|\\*))?$");

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

    private CreditModelAllowlist() {
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
                errors.add(new FieldValidationError(at,
                        "모든 모델을 허용하려면 목록을 비워 주세요. '*' 하나만 적을 수는 없습니다."));
                continue;
            }
            if (isReserved(value)) {
                errors.add(new FieldValidationError(at,
                        "자체 서빙 모델은 이 목록의 대상이 아닙니다. 상용 모델 이름을 적어 주세요."));
                continue;
            }
            if (!PATTERN.matcher(value).matches()) {
                errors.add(new FieldValidationError(at,
                        "모델 이름 또는 벤더 프리픽스(예: openai/*) 형식이어야 합니다."));
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
     */
    public static List<String> fromJson(ObjectMapper mapper, @Nullable String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = mapper.readTree(stored);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> models = new ArrayList<>(node.size());
            node.forEach(item -> {
                if (item.isString() && !item.asString().isBlank()) {
                    models.add(item.asString());
                }
            });
            return List.copyOf(models);
        } catch (JacksonException e) {
            return List.of();
        }
    }
}
