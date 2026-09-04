package kr.ac.pusan.pickle.llm.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.admin.AdminOpenRouterCatalogueService;
import kr.ac.pusan.pickle.admin.dto.OpenRouterCatalogueResponse;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class OpenRouterCatalogueTest {

    @Autowired private OpenRouterCatalogueRepository catalogue;
    @Autowired private OpenRouterCatalogueRefreshJob refreshJob;
    @Autowired private AdminOpenRouterCatalogueService service;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private OpenRouterClient client;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("delete from openrouter_catalogue_model");
        jdbcTemplate.update("delete from openrouter_catalogue_state");
    }

    private static OpenRouterClient.VendorModel model(String id, String completion) {
        return new OpenRouterClient.VendorModel(id, "Name of " + id, "desc", 128000,
                new BigDecimal("0.0000001"),
                completion == null ? null : new BigDecimal(completion));
    }

    @Test
    void refreshStoresTheListingAndItsFreshness() {
        when(client.catalogue()).thenReturn(List.of(
                model("openai/gpt-4o-mini", "0.0000006"),
                model("~anthropic/claude-sonnet-latest", "0.00001")));

        refreshJob.refresh();

        OpenRouterCatalogueResponse response = service.catalogue();
        assertThat(response.models()).extracting(OpenRouterCatalogueResponse.CatalogueModel::id)
                .containsExactly("openai/gpt-4o-mini", "~anthropic/claude-sonnet-latest");
        assertThat(response.freshness()).isEqualTo(OpenRouterCreditsFreshness.FRESH);
        assertThat(response.consecutiveFailures()).isZero();
        assertThat(response.lastError()).isNull();
    }

    /**
     * Per-token in the column, per-million on the screen. A model priced at
     * $0.6 per million tokens is the difference between a number a person can
     * compare and one they cannot.
     */
    @Test
    void showsPricesPerMillionTokens() {
        when(client.catalogue()).thenReturn(List.of(model("openai/gpt-4o-mini", "0.0000006")));
        refreshJob.refresh();

        assertThat(service.catalogue().models().getFirst().completionPricePerMillion())
                .isEqualByComparingTo("0.6");
    }

    /**
     * The whole reason delisted rows are kept: a model that leaves the vendor's
     * list is still named by fences that were approved while it was there.
     */
    @Test
    void aModelThatLeavesTheListingKeepsItsRow() {
        when(client.catalogue()).thenReturn(List.of(
                model("openai/gpt-4o-mini", "0.0000006"), model("vendor/retired", "0.000002")));
        refreshJob.refresh();

        when(client.catalogue()).thenReturn(List.of(model("openai/gpt-4o-mini", "0.0000006")));
        refreshJob.refresh();

        assertThat(service.catalogue().models())
                .extracting(OpenRouterCatalogueResponse.CatalogueModel::id)
                .containsExactly("openai/gpt-4o-mini");
        assertThat(jdbcTemplate.queryForObject(
                "select listed from openrouter_catalogue_model where model_id = 'vendor/retired'",
                Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "select delisted_at is not null from openrouter_catalogue_model "
                        + "where model_id = 'vendor/retired'", Boolean.class)).isTrue();
    }

    /**
     * A vendor that answers with nothing has failed; it has not told us the
     * catalogue is empty. Treating the two the same would switch every model
     * off at once on one bad response, which is exactly what keeping delisted
     * rows is meant to prevent.
     */
    @Test
    void anEmptyAnswerNeverEmptiesTheCatalogue() {
        when(client.catalogue()).thenReturn(List.of(model("openai/gpt-4o-mini", "0.0000006")));
        refreshJob.refresh();

        // Spring's @Repository translation wraps the guard's IllegalArgumentException,
        // so the assertion is on the refusal rather than on the wrapper's type.
        assertThatThrownBy(() -> catalogue.replaceListing(List.of(), Instant.now()))
                .hasMessageContaining("refusing to apply an empty catalogue")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(service.catalogue().models()).hasSize(1);
    }

    /**
     * A failed refresh must leave the listing alone and must not move the clock
     * freshness is judged against — otherwise a stale list reads as current.
     */
    @Test
    void aFailedRefreshKeepsTheListingAndDoesNotRefreshTheClock() {
        when(client.catalogue()).thenReturn(List.of(model("openai/gpt-4o-mini", "0.0000006")));
        refreshJob.refresh();
        Instant firstSuccess = service.catalogue().lastSuccessAt();

        when(client.catalogue()).thenThrow(new OpenRouterException(503, "vendor down"));
        refreshJob.refresh();

        OpenRouterCatalogueResponse after = service.catalogue();
        assertThat(after.models()).hasSize(1);
        assertThat(after.lastSuccessAt()).isEqualTo(firstSuccess);
        assertThat(after.consecutiveFailures()).isEqualTo(1);
        assertThat(after.lastError()).contains("VENDOR_UNAVAILABLE");
        assertThat(after.lastAttemptAt()).isAfterOrEqualTo(firstSuccess);
    }

    /**
     * Never refreshed and refreshed-but-old are different answers. A screen
     * that shows one blank for both leaves the approver unable to tell a new
     * deployment from a broken vendor.
     */
    @Test
    void neverRefreshedReadsAsUnknownRatherThanStale() {
        OpenRouterCatalogueResponse response = service.catalogue();

        assertThat(response.models()).isEmpty();
        assertThat(response.freshness()).isEqualTo(OpenRouterCreditsFreshness.UNKNOWN);
        assertThat(response.lastSuccessAt()).isNull();
        assertThat(response.consecutiveFailures()).isZero();
    }

    /** The vendor's error body is not stored; the classification is. */
    @Test
    void theVendorBodyIsNotStored() {
        when(client.catalogue())
                .thenThrow(new OpenRouterException(429, "slow down, quota is 1234 for tenant x"));
        refreshJob.refresh();

        assertThat(service.catalogue().lastError())
                .isEqualTo("THROTTLED (HTTP 429)")
                .doesNotContain("tenant");
    }
}
