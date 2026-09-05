package kr.ac.pusan.pickle.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.admin.dto.OpenRouterCatalogueResponse;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCatalogueRefreshJob;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCatalogueRepository;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCreditsFreshness;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves the cached vendor catalogue.
 *
 * <p>Reads the cache only. Fetching on request would put a vendor call on the
 * path of opening an approval screen, and the one thing this feature must not
 * do is make an approval wait on the vendor being up.
 */
@Service
@Transactional(readOnly = true)
public class AdminOpenRouterCatalogueService {

    /**
     * Prices are stored per token because that is how the vendor states them,
     * and shown per million because that is the unit a person can compare. The
     * conversion lives here rather than in the column so the stored value stays
     * the vendor's own.
     */
    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");


    private final OpenRouterCatalogueRepository catalogue;
    private final Clock clock;

    public AdminOpenRouterCatalogueService(OpenRouterCatalogueRepository catalogue, Clock clock) {
        this.catalogue = catalogue;
        this.clock = clock;
    }

    public OpenRouterCatalogueResponse catalogue() {
        OpenRouterCatalogueRepository.CatalogueState state = catalogue.state();
        List<OpenRouterCatalogueResponse.OpenRouterCatalogueModel> models = catalogue.listed().stream()
                .map(row -> new OpenRouterCatalogueResponse.OpenRouterCatalogueModel(
                        row.modelId(), row.displayName(), perMillion(row.promptPrice()),
                        perMillion(row.completionPrice()), row.contextLength()))
                .toList();
        return new OpenRouterCatalogueResponse(models, freshness(state.lastSuccessAt()),
                state.lastSuccessAt(), state.lastAttemptAt(), state.lastError(),
                state.consecutiveFailures());
    }

    private OpenRouterCreditsFreshness freshness(@Nullable Instant lastSuccessAt) {
        return OpenRouterCreditsFreshness.of(lastSuccessAt,
                OpenRouterCatalogueRefreshJob.staleAfter(), clock);
    }

    /**
     * A null price stays null: unknown and free are different, and rendering
     * one as the other would put a model that costs an unknown amount next to
     * the free tier in a list an approver reads to control spending.
     */
    private static @Nullable BigDecimal perMillion(@Nullable BigDecimal perToken) {
        return perToken == null ? null
                : perToken.multiply(PER_MILLION).setScale(6, RoundingMode.HALF_UP);
    }
}
