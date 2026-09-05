package kr.ac.pusan.pickle.llm;

import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceAccessResolver;
import kr.ac.pusan.pickle.access.ResourceStanding;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.llm.dto.LlmKeyModelsResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyModelsResponse.CatalogFreshness;
import kr.ac.pusan.pickle.llm.dto.LlmKeyModelsResponse.PaidAccess;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCatalogueRefreshJob;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCatalogueRepository;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCreditsFreshness;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The models one key may call.
 *
 * <p>No vendor call happens here. The paid listing is whatever the refresh job
 * last stored, so this screen keeps answering while the vendor is down — it
 * says the listing is old instead of failing, which is the honest difference
 * between "we cannot reach them" and "you cannot call this".
 *
 * <p>Per-key facts are decided here: a key with no budget, a model outside the
 * key's allow list. What is left to the gateway is what this side cannot know —
 * whether the vendor serves a listed model to the account behind this key.
 *
 * <p><b>Two platform-wide conditions are deliberately not consulted</b>, and
 * the boundary is per-key rather than per-request for the same reason the list
 * is sent to a key with no budget at all: it says what the names are, and an
 * operational condition that will be lifted should not erase them.
 *
 * <ul>
 * <li>Whether the passthrough upstream is enabled. With it off the gateway
 * answers every paid name with a not-found, and this listing still fills.</li>
 * <li>Money-axis rows in this platform's own catalogue. The self-served half
 * queries the token axis only, and the paid half is the vendor cache, so such a
 * row would appear in neither. The operational inventory carries none today,
 * which is why this is a note and not a branch.</li>
 * </ul>
 */
@Service
public class LlmKeyModelService {

    /**
     * Vendors whose models go first.
     *
     * <p>The vendor catalogue carries no popularity signal, and ordering by
     * price alone floats models nobody uses above the ones people came for: the
     * cheapest entry in the listing costs a fifth of the model this service was
     * built to surface. Vendor order is the stand-in, and the maintenance is
     * this list rather than a curated set of models.
     *
     * <p>Ordering by real usage on this platform is the better answer and is
     * recorded as pending work; the paid-axis history is too thin to rank with
     * today.
     */
    private static final List<String> VENDOR_ORDER = List.of(
            "openai", "anthropic", "google", "meta", "mistral", "deepseek", "qwen");

    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");

    private final LlmApiKeyRepository keyRepository;
    private final ResourceAccessResolver resourceAccessResolver;
    private final OpenRouterCatalogueRepository catalogue;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LlmKeyModelService(LlmApiKeyRepository keyRepository,
            ResourceAccessResolver resourceAccessResolver,
            OpenRouterCatalogueRepository catalogue, JdbcTemplate jdbc,
            ObjectMapper objectMapper, Clock clock) {
        this.keyRepository = keyRepository;
        this.resourceAccessResolver = resourceAccessResolver;
        this.catalogue = catalogue;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Same visibility as the key's detail: this is its content, not its state.
     *
     * <p>Grants decide it, not account role, so a system administrator with no
     * grant is answered exactly as any other non-member is. The administrative
     * surface is a separate path with its own rule rather than a widening of
     * this one, so which one opens what stays readable from the route.
     */
    @Transactional(readOnly = true)
    public LlmKeyModelsResponse list(AuthenticatedUser actor, UUID keyId) {
        LlmApiKey key = keyRepository.findByPublicId(keyId)
                .orElseThrow(() -> LlmKeyResourceAdapter.MESSAGES.notFound());
        ResourceStanding standing = resourceAccessResolver.standing(ResourceType.LLM_API_KEY,
                key.getId(), key.getWorkspaceId(), actor.id());
        standing.requireVisible(LlmKeyResourceAdapter.MESSAGES);
        return of(key);
    }

    /**
     * The same answer for a key the caller has already been cleared to read.
     *
     * <p><b>Do not call this from a controller, an event handler or a job.</b>
     * It carries no authorization, and holding the entity is not a substitute
     * for one: plenty of code reaches a key without ever asking whether this
     * requester may see it. Callers today are exactly two, each immediately
     * after its own check, and a third belongs beside a check of its own.
     *
     * <p>It takes the entity rather than an id to say that in the signature: a
     * caller that has one has already been somewhere that looked the key up,
     * and the compiler cannot say whether that place also authorized. The
     * administrative path pairs this with institution scoping and the holder's
     * with resource grants, so the two differ in who may ask and never in what
     * the answer is. A second copy of the narrowing would be a second answer to
     * "what may this key call", and the screens would disagree about a fence.
     */
    @Transactional(readOnly = true)
    public LlmKeyModelsResponse of(LlmApiKey key) {
        return new LlmKeyModelsResponse(selfServed(), paid(key));
    }

    /**
     * The catalogue rows a key can reach. Three conditions narrow it and they
     * are not the same kind of thing: disabled is a row switched off, restricted
     * is a row no key reaches today (the document this platform sends the
     * gateway carries no per-key model grants, so listing it would be listing
     * something nothing can call), and the token axis is what this half of the
     * response means — a money-axis row here is a catalogue row, not a
     * self-served model.
     */
    private List<LlmKeyModelsResponse.SelfServedModel> selfServed() {
        return jdbc.query("""
                select public_name, max_input_tokens, max_output_tokens
                  from llm_models
                 where enabled
                   and budget_axis = 'TOKEN'
                   and visibility = 'PUBLIC'
                 order by public_name
                """, (rs, i) -> new LlmKeyModelsResponse.SelfServedModel(
                        rs.getString("public_name"),
                        // Zero is how the column spells "no cap"; the screen
                        // should say nothing rather than say zero.
                        positiveOrNull(rs.getInt("max_input_tokens")),
                        positiveOrNull(rs.getInt("max_output_tokens"))));
    }

    private LlmKeyModelsResponse.PaidModels paid(LlmApiKey key) {
        List<String> patterns = CreditModelPatterns.fromJson(objectMapper,
                key.getCreditAllowedModels(), "llm key " + key.getPublicId());
        OpenRouterCatalogueRepository.CatalogueState state = catalogue.state();
        CatalogFreshness freshness = translate(OpenRouterCreditsFreshness.of(
                state.lastSuccessAt(), OpenRouterCatalogueRefreshJob.staleAfter(), clock));

        List<String> denied = CreditModelPatterns.fromJson(objectMapper,
                key.getCreditDeniedModels(), "llm key " + key.getPublicId());

        // Both fences narrow here, in the order the gateway applies them: the
        // allow list decides what is in scope, then the deny list removes from
        // it, and deny wins wherever the two name the same model. Subtracting
        // second is what makes that true — a denied model listed here would be
        // a model this screen promises and the gateway refuses.
        //
        // The subtraction belongs in this method and nowhere else. Both the
        // administrative route and the holder's answer from this one
        // computation, so a second copy would let the two screens disagree
        // about a fence, and the one an approver reads is the one that decides
        // what gets granted.
        List<OpenRouterCatalogueRepository.CatalogueRow> rows = catalogue.listed();
        List<OpenRouterCatalogueRepository.CatalogueRow> reachable = rows.stream()
                .filter(row -> patterns.isEmpty() || matchesAny(patterns, row.modelId()))
                .filter(row -> !matchesAny(denied, row.modelId()))
                .toList();

        // An entry that matched nothing is worth saying out loud on either
        // list, but the two facts do not mean the same thing and the response
        // keeps them apart. On the allow side it is mostly a typo, a model the
        // vendor withdrew, or a listing too old to hold it. On the deny side it
        // is equally likely to be deliberate: blocking a tier before it ships
        // is a thing reviewers do, and the catalogue gains models weekly. The
        // server cannot tell which, so it reports the fact and leaves the
        // reading to the person who wrote the entry.
        return new LlmKeyModelsResponse.PaidModels(access(key, patterns, denied), patterns,
                denied, sorted(reachable), unmatchedAgainst(patterns, rows),
                unmatchedAgainst(denied, rows), freshness, state.lastSuccessAt());
    }

    /**
     * The listing is sent in every state, including a key with no budget: what
     * that holder needs next is to know what to ask for, and a request for
     * something they cannot name is a request they cannot make.
     */
    private PaidAccess access(LlmApiKey key, List<String> patterns, List<String> denied) {
        if (key.getCreditLimit().signum() <= 0) {
            return PaidAccess.NONE;
        }
        if (!key.isCreditAxisConnected()) {
            return PaidAccess.PENDING;
        }
        // Either list makes this a listing. UNRESTRICTED asserts that nothing
        // but money bounds what the key may call, and on a key with a deny list
        // that assertion is false: something was refused, somebody refused it,
        // and the refusal is stored on the row. Answering from the allow list
        // alone would be true to the question this method asks itself and false
        // to the one a reviewer reads, who sees the label above the narrowed
        // list and has to decide which of the two to believe.
        return patterns.isEmpty() && denied.isEmpty()
                ? PaidAccess.UNRESTRICTED : PaidAccess.LISTED;
    }

    /** The entries of one list that no catalogue row answers to. */
    private static List<String> unmatchedAgainst(List<String> patterns,
            List<OpenRouterCatalogueRepository.CatalogueRow> rows) {
        List<String> unmatched = new ArrayList<>();
        for (String pattern : patterns) {
            if (rows.stream().noneMatch(row -> CreditModelPatterns.matches(pattern, row.modelId()))) {
                unmatched.add(pattern);
            }
        }
        return unmatched;
    }

    private static boolean matchesAny(List<String> patterns, String modelId) {
        return patterns.stream().anyMatch(pattern -> CreditModelPatterns.matches(pattern, modelId));
    }

    private static List<LlmKeyModelsResponse.PaidModel> sorted(
            List<OpenRouterCatalogueRepository.CatalogueRow> rows) {
        return rows.stream()
                .sorted(Comparator
                        .comparingInt((OpenRouterCatalogueRepository.CatalogueRow row)
                                -> vendorRank(row.modelId()))
                        // Input price, not output: an agent tool resends its
                        // whole prompt every turn, so input is what a caller
                        // actually spends.
                        .thenComparing(row -> row.promptPrice(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(OpenRouterCatalogueRepository.CatalogueRow::modelId))
                .map(row -> new LlmKeyModelsResponse.PaidModel(row.modelId(), row.displayName(),
                        perMillion(row.promptPrice()), perMillion(row.completionPrice()),
                        row.contextLength()))
                .toList();
    }

    private static int vendorRank(String modelId) {
        int slash = modelId.indexOf('/');
        if (slash <= 0) {
            return VENDOR_ORDER.size();
        }
        String vendor = modelId.substring(0, slash);
        // A floating alias carries a leading tilde on the vendor half.
        vendor = vendor.startsWith("~") ? vendor.substring(1) : vendor;
        int rank = VENDOR_ORDER.indexOf(vendor);
        return rank < 0 ? VENDOR_ORDER.size() : rank;
    }

    /**
     * Per-token to per-million. A null price stays null: unknown and free are
     * different, and the console renders them differently.
     */
    private static @Nullable BigDecimal perMillion(@Nullable BigDecimal perToken) {
        if (perToken == null) {
            return null;
        }
        // Scaling by a power of ten is exact, so no rounding mode is involved.
        // A negative scale after stripping (1E+2) is pulled back to plain
        // digits so the JSON carries 100 rather than an exponent.
        BigDecimal scaled = perToken.multiply(PER_MILLION).stripTrailingZeros();
        return scaled.scale() < 0 ? scaled.setScale(0) : scaled;
    }

    private static @Nullable Integer positiveOrNull(int value) {
        return value > 0 ? value : null;
    }

    /**
     * The published freshness type is named for credits because that was its
     * first use. The student-facing contract gets its own three states rather
     * than that name, and this is the one place the two meet.
     */
    private static CatalogFreshness translate(OpenRouterCreditsFreshness freshness) {
        return switch (freshness) {
            case FRESH -> CatalogFreshness.FRESH;
            case STALE -> CatalogFreshness.STALE;
            case UNKNOWN -> CatalogFreshness.UNKNOWN;
        };
    }
}
