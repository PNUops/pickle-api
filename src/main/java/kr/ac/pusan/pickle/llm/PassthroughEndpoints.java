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
 * The capabilities a key may use: normalization, validation and the JSON form
 * the columns store.
 *
 * <p>A sibling of {@link CreditModelPatterns} in shape and a deliberate
 * opposite in meaning. <b>Empty means "no capability at all".</b> The two
 * model lists say an empty list places no constraint; this one has to default
 * to closed, because a route nobody granted must not open by the mere act of
 * the gateway learning to serve it.
 *
 * <p><b>Chat is a capability too.</b> V111 kept chat completions outside this
 * list, so every key reached them without a grant. V139 moved chat inside: a
 * key that needs no text generation (a course that only makes video) should
 * not hold it, and a key that holds it can be wired to a coding agent that
 * spends money. {@code chat} is the one capability that applies to both
 * axes; every other one is money-axis only.
 *
 * <p><b>Entries name a capability, not a path.</b> The approval screen shows
 * this vocabulary and an approver has to read it and decide, so {@code images}
 * carries better than a path plus the catalogue read that belongs with it. It
 * also survives the vendor adding a sub-path under a capability already
 * granted, which a path list would not.
 *
 * <p><b>Three sets, in order.</b> {@link #KNOWN} is the closed vocabulary the
 * DB CHECK holds and the contract enumerates: chat and every route the
 * gateway is expected to open after it. {@link #GRANTABLE} is the part of it a write may grant
 * today, widened here as each route opens, so a grant can never name a route
 * the gateway does not serve yet. The hierarchy then holds between entries:
 * {@code web_search}, {@code pdf_ocr} and {@code containers} are features of
 * chat and need it; {@code files} exists only to feed {@code batches} or
 * {@code containers}, so it follows them rather than being chosen.
 */
public final class PassthroughEndpoints {

    private static final Logger log = LoggerFactory.getLogger(PassthroughEndpoints.class);

    /** The empty list in its stored form. */
    public static final String EMPTY_JSON = "[]";

    /** Text generation: chat completions and every route that speaks another chat protocol. */
    public static final String CHAT = "chat";

    /** Web search inside a chat request. Under {@link #CHAT}. */
    public static final String WEB_SEARCH = "web_search";

    /** Paid OCR of a PDF inside a chat request. Under {@link #CHAT}. */
    public static final String PDF_OCR = "pdf_ocr";

    /** Code execution in a vendor sandbox. Under {@link #CHAT}. */
    public static final String CONTAINERS = "containers";

    /** Image generation, and the image model catalogue that goes with it. */
    public static final String IMAGES = "images";

    /** Embedding vectors. */
    public static final String EMBEDDINGS = "embeddings";

    /** Reranking documents against a query. */
    public static final String RERANK = "rerank";

    /** Text to speech. */
    public static final String SPEECH = "speech";

    /** Speech to text. */
    public static final String TRANSCRIPTIONS = "transcriptions";

    /** Video generation. */
    public static final String VIDEOS = "videos";

    /** Batch submission. */
    public static final String BATCHES = "batches";

    /** File upload. Follows {@link #BATCHES} or {@link #CONTAINERS}. */
    public static final String FILES = "files";

    /**
     * The closed vocabulary, in the order the contract lists it, matching the
     * {@code in} list of the DB CHECK V139 installed. Both sides have to move
     * together; the CHECK is what actually refuses a bad write, and this is
     * what tells a reviewer why.
     */
    public static final List<String> KNOWN = List.of(CHAT, WEB_SEARCH, PDF_OCR, CONTAINERS,
            IMAGES, EMBEDDINGS, RERANK, SPEECH, TRANSCRIPTIONS, VIDEOS, BATCHES, FILES);

    /**
     * What a write may grant today, in the same order. A name joins this when
     * the gateway serves its route; until then granting it would describe a
     * permission neither side can exercise.
     */
    public static final List<String> GRANTABLE = List.of(CHAT, WEB_SEARCH, PDF_OCR,
            IMAGES, EMBEDDINGS, RERANK, SPEECH, TRANSCRIPTIONS);

    /** Capabilities that are features of a chat request and mean nothing without it. */
    private static final Set<String> UNDER_CHAT = Set.of(WEB_SEARCH, PDF_OCR, CONTAINERS);

    /** Capabilities that need {@link #FILES}; it is granted with either and dropped with both. */
    private static final Set<String> FILE_USERS = Set.of(BATCHES, CONTAINERS);

    /**
     * Matches the DB CHECK. Unreachable through the request DTOs, which carry a
     * size bound of their own, but this method is the one place that decides
     * what a stored list may be.
     */
    private static final int MAX_ENTRIES = 20;

    private PassthroughEndpoints() {
    }

    /**
     * Normalizes the entries of a submitted list: lower-cased, trimmed, blanks
     * and duplicates removed, the order somebody typed kept. Each entry has to
     * be one a write may grant.
     *
     * <p>Entries only: the hierarchy is a property of a whole grant, and this is
     * also what checks the values of a bulk ADD or REMOVE, where the list a
     * key ends up with is not known yet. A whole grant goes through
     * {@link #normalizeGrant} instead.
     *
     * <p>An unknown or ungrantable token is an error rather than a silent drop.
     * Dropping it would save a form that grants less than the approver read on
     * the screen, and this list is what grants.
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
            if (!GRANTABLE.contains(value)) {
                errors.add(new FieldValidationError(field + "[" + i + "]",
                        (KNOWN.contains(value) ? "아직 부여할 수 없는 기능입니다. "
                                : "알 수 없는 기능입니다. ")
                                + String.join(", ", GRANTABLE) + " 중에서 골라 주세요."));
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

    /**
     * Normalizes a whole grant: the entries as {@link #normalize} does, then
     * the hierarchy. A feature of chat without chat is refused, because the
     * approver would read a grant the gateway cannot honour. {@code files} is
     * added when a capability that needs it is present and removed when none
     * is, so it never has to be chosen. Neither {@code containers},
     * {@code batches} nor {@code files} is grantable yet, so the entry check
     * refuses them before this point and that half of the hierarchy takes
     * effect only once {@link #GRANTABLE} widens.
     *
     * <p>Every write that stores a list lands here: the request's own choice,
     * the approval, the single-key replacement and the merged result of a bulk
     * change.
     */
    public static List<String> normalizeGrant(@Nullable List<String> raw, String field,
            List<FieldValidationError> errors) {
        int before = errors.size();
        List<String> entries = normalize(raw, field, errors);
        if (errors.size() > before) {
            return List.of();
        }
        if (!entries.contains(CHAT)) {
            for (String entry : entries) {
                if (UNDER_CHAT.contains(entry)) {
                    errors.add(new FieldValidationError(field,
                            entry + " 기능은 chat 기능과 함께 부여해야 합니다."));
                    return List.of();
                }
            }
        }
        LinkedHashSet<String> kept = new LinkedHashSet<>(entries);
        if (entries.stream().anyMatch(FILE_USERS::contains)) {
            kept.add(FILES);
        } else {
            kept.remove(FILES);
        }
        return List.copyOf(kept);
    }

    /**
     * The list without {@code chat}, as a set. What decides whether a change
     * moves anything on the money side: chat spans both axes, so granting or
     * withdrawing it alone is not a money decision.
     */
    public static Set<String> withoutChat(List<String> endpoints) {
        Set<String> rest = new java.util.HashSet<>(endpoints);
        rest.remove(CHAT);
        return rest;
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
     * neither side applies. The filter is {@link #KNOWN}, not
     * {@link #GRANTABLE}: a stored name is carried even before it may be newly
     * granted, so widening the grantable set never has to rewrite rows.
     *
     * <p><b>This is why rolling back has an order.</b> A build that predates a
     * name drops it here, from the document too. Once the gateway enforces
     * {@code chat}, an api that does not know the name serves every key without
     * it, so the gateway goes back first.
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
