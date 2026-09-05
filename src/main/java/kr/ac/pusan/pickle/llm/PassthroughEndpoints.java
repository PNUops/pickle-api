package kr.ac.pusan.pickle.llm;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The passthrough surface a key may reach: normalization, validation and the
 * JSON form the columns store.
 *
 * <p>A sibling of {@link CreditModelPatterns} in shape and a deliberate
 * opposite in meaning. <b>Empty means "no passthrough path at all".</b> The two
 * model lists say an empty list places no constraint; this one has to default
 * to closed, because a path nobody granted must not open by the mere act of the
 * gateway learning to serve it.
 *
 * <p>Keeping the two apart costs nothing because the axes do not overlap. This
 * list governs only the paths the passthrough surface adds. Chat completions
 * and the model catalogue are outside it and keep the fences they already have,
 * so an empty list takes nothing away from a key that exists now. The same
 * reasoning is what makes an old control plane safe against a newer gateway:
 * a member it never sends reads as empty, empty means no passthrough path, and
 * those are exactly the paths an old gateway does not serve either.
 *
 * <p><b>Entries name a capability, not a path.</b> The approval screen shows
 * this vocabulary and an approver has to read it and decide, so {@code images}
 * carries better than a path plus the catalogue read that belongs with it. It
 * also survives the vendor adding a sub-path under a capability already
 * granted, which a path list would not.
 *
 * <p><b>The set is closed.</b> An unknown token cannot be stored, so a typo
 * fails at the write rather than becoming a permission nobody can explain
 * later. Adding a capability is a migration and a change here, which is the
 * right cost: it is a decision about what the platform resells.
 */
public final class PassthroughEndpoints {

    private static final Logger log = LoggerFactory.getLogger(PassthroughEndpoints.class);

    /** The empty list in its stored form, and the default for every column. */
    public static final String EMPTY_JSON = "[]";

    /** Image generation and editing, and the image model catalogue that goes with it. */
    public static final String IMAGES = "images";

    /** Embedding vectors. */
    public static final String EMBEDDINGS = "embeddings";

    /**
     * The closed vocabulary, matching the {@code in} list of the DB CHECK
     * installed by V111. Both sides have to move together; the CHECK is what
     * actually refuses a bad write, and this is what tells a reviewer why.
     */
    public static final Set<String> KNOWN = Set.of(IMAGES, EMBEDDINGS);

    /**
     * Matches the DB CHECK. Unreachable through the request DTOs, which carry a
     * size bound of their own, but this method is the one place that decides
     * what a stored list may be.
     */
    private static final int MAX_ENTRIES = 20;

    private PassthroughEndpoints() {
    }

    /**
     * Normalizes a submitted list: lower-cased, trimmed, blanks and duplicates
     * removed, the order somebody typed kept.
     *
     * <p>An unknown token is an error rather than a silent drop. Dropping it
     * would save a form that grants less than the approver read on the screen,
     * and this list is what grants.
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
            if (!KNOWN.contains(value)) {
                errors.add(new FieldValidationError(field + "[" + i + "]",
                        "알 수 없는 기능입니다. " + String.join(", ", sorted()) + " 중에서 골라 주세요."));
                continue;
            }
            kept.add(value);
        }
        if (kept.size() > MAX_ENTRIES) {
            errors.add(new FieldValidationError(field,
                    "기능은 최대 " + MAX_ENTRIES + "개까지 허용할 수 있습니다."));
            return List.of();
        }
        return List.copyOf(kept);
    }

    /** The vocabulary in a stable order, for a message a person reads. */
    public static List<String> sorted() {
        return KNOWN.stream().sorted().toList();
    }

    /** The stored form. Never null, so the column's not-null holds trivially. */
    public static String toJson(ObjectMapper mapper, List<String> endpoints) {
        if (endpoints.isEmpty()) {
            return EMPTY_JSON;
        }
        return mapper.writeValueAsString(endpoints);
    }

    /**
     * Reads a stored value back. An unreadable row reads as empty.
     *
     * <p><b>Here that is the safe direction, and it is the one place this class
     * is luckier than its sibling.</b> An unreadable model deny list read as
     * empty reopens exactly the models somebody refused; an unreadable list
     * here closes every passthrough path for that key, which is visible to its
     * owner immediately and grants nothing. So the leniency that costs
     * {@link CreditModelPatterns} a documented hole costs nothing on this axis.
     *
     * <p>An entry outside the vocabulary is dropped rather than kept, for the
     * same reason: a token this build does not know is a permission it cannot
     * enforce, and carrying it into the gateway document would describe a fence
     * neither side applies.
     */
    public static List<String> fromJson(ObjectMapper mapper, @Nullable String stored,
            String source) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = mapper.readTree(stored);
            if (!node.isArray()) {
                log.warn("stored passthrough endpoint list for {} is not a JSON array; reading "
                        + "it as empty, which closes every passthrough path for that key", source);
                return List.of();
            }
            LinkedHashSet<String> kept = new LinkedHashSet<>();
            node.forEach(item -> {
                if (item.isString() && KNOWN.contains(item.asString())) {
                    kept.add(item.asString());
                }
            });
            if (kept.size() != node.size()) {
                log.warn("dropped {} unknown or unreadable entr(ies) from the stored passthrough "
                        + "endpoint list for {}; those paths stay closed for that key",
                        node.size() - kept.size(), source);
            }
            return List.copyOf(kept);
        } catch (JacksonException e) {
            log.warn("could not parse the stored passthrough endpoint list for {}; reading it as "
                    + "empty, which closes every passthrough path for that key", source, e);
            return List.of();
        }
    }
}
