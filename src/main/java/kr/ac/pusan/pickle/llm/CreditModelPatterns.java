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

    /**
     * Matches the DB CHECK installed by V102 and widened by V104 and V109 — a
     * model name or a vendor prefix, optionally carrying the vendor's leading
     * tilde, with at most one star in the segment after the slash.
     *
     * <p>The segment takes four shapes: an exact name, a name with a trailing
     * star, a leading star with a non-empty tail ending alphanumeric, or a bare
     * star for the whole vendor. The vendor half takes no star at all, because
     * vendor names are prefixes of one another — {@code meta} and {@code
     * meta-llama}, {@code bytedance} and {@code bytedance-seed} — so {@code
     * openai*} would reach a vendor nobody named. The slash itself stays
     * optional: vendorless names exist and are in use.
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
    private static final Pattern PATTERN = Pattern.compile(
            "^~?[a-z0-9][a-z0-9._:-]*(/([a-z0-9][a-z0-9._:-]*\\*?"
                    + "|\\*[a-z0-9._:-]*[a-z0-9]|\\*))?$");

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
                                + "않고, 한 벤더 전체는 'openai/*'처럼 적습니다."));
                continue;
            }
            if (isReserved(value)) {
                errors.add(new FieldValidationError(at,
                        "자체 서빙 모델은 이 목록의 대상이 아닙니다. 유료 모델 이름을 적어 주세요."));
                continue;
            }
            if (!PATTERN.matcher(value).matches()) {
                errors.add(new FieldValidationError(at,
                        "모델 이름(예: openai/gpt-4o-mini), 벤더 전체(예: openai/*), 또는 "
                                + "와일드카드를 하나 포함한 모델 패턴(예: openai/gpt-5-*, "
                                + "openai/*-pro) 형식이어야 합니다."));
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
     * Whether one normalized pattern covers a model name.
     *
     * <p><b>This is the second piece of gateway knowledge duplicated here</b>,
     * and unlike the reserved prefixes above it is not a courtesy: a screen that
     * lists what a key may call has to agree with the fence, or it shows models
     * the call will refuse. The rule is the gateway's, transcribed step for
     * step, and the case table in {@code CreditModelPatternsTest} is kept input
     * for input with the gateway's own. <b>Keeping those two tables equal is the
     * only thing holding this copy together</b>, so move them together or the
     * divergence will be silent: every case in a stale table still passes.
     *
     * <p>The model segment takes four shapes — an exact name, a trailing star,
     * a leading star, or a bare star for the whole vendor. Two of them carry a
     * rule that is easy to lose:
     *
     * <ul>
     * <li><b>A trailing star stands for nothing at all</b>, as a glob's does, so
     * {@code openai/gpt-5*} reaches {@code openai/gpt-5} itself. Requiring a
     * character made the wider-looking pattern the narrower one, which is the
     * kind of surprise nobody debugs.</li>
     * <li><b>A leading star sees through a variant suffix.</b> {@code :batch}
     * and {@code :free} name the same model at a different price, so
     * {@code openai/*-pro} must reach {@code openai/gpt-5-pro:batch} — without
     * that it catches the cheap spelling and misses the expensive one.</li>
     * </ul>
     *
     * <p>The separator rule stays beside the trailing star: {@code openai/gpt-5-*}
     * reaches {@code openai/gpt-5} even though the two are not in a prefix
     * relation. A bare {@code *} matches nothing — "everything" is spelled by an
     * empty list, and passthrough can synthesize a model named exactly that. A
     * leading {@code ~} is an ordinary character, so {@code ~vendor/*} and
     * {@code vendor/*} stay separate prefixes.
     *
     * <p>Both sides lower-case before comparing. A change to the gateway's
     * matcher wants a change here in the same unit of work.
     */
    public static boolean matches(String pattern, String modelName) {
        String p = pattern.toLowerCase(Locale.ROOT);
        String n = modelName.toLowerCase(Locale.ROOT);
        if (p.isEmpty() || "*".equals(p)) {
            return false;
        }
        int slash = p.indexOf('/');
        if (slash < 0) {
            return p.equals(n);
        }
        String prefix = p.substring(0, slash) + "/";
        if (!n.startsWith(prefix)) {
            return false;
        }
        String rest = n.substring(prefix.length());
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
        return rest.equals(seg);
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
