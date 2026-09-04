package kr.ac.pusan.pickle.llm.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
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
        return new OpenRouterClient.VendorModel(id, "Name of " + id, 128000,
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
        assertThat(response.models()).extracting(OpenRouterCatalogueResponse.OpenRouterCatalogueModel::id)
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
                .extracting(OpenRouterCatalogueResponse.OpenRouterCatalogueModel::id)
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

    /**
     * The write failing is a failed refresh, not a state nobody records.
     *
     * <p>This is the shape that shipped as a defect: the vendor publishes a
     * negative sentinel on its router models, it parsed as a price, and the
     * column refused it. With the write outside the try the exception escaped
     * the job, {@code recordFailure} never ran, and the screen said "not
     * fetched yet" — the message a brand-new deployment shows — every hour,
     * forever.
     */
    @Test
    void aWriteThatFailsIsRecordedAsAFailure() {
        when(client.catalogue()).thenReturn(List.of(
                new OpenRouterClient.VendorModel("vendor/impossible", "Impossible", 1000,
                        new BigDecimal("-1"), new BigDecimal("-1"))));

        refreshJob.refresh();

        OpenRouterCatalogueResponse response = service.catalogue();
        assertThat(response.consecutiveFailures())
                .describedAs("a write that throws must leave a recorded failure, not silence")
                .isEqualTo(1);
        assertThat(response.lastError()).isEqualTo("UNEXPECTED");
        assertThat(response.lastAttemptAt()).isNotNull();
        assertThat(response.lastSuccessAt()).isNull();
    }

    /**
     * A refusal at 401 or 403 on a call that carries no credential is not a
     * credential problem. Saying so would send an administrator to rotate a key
     * that is not involved.
     */
    @Test
    void aBlockedRequestIsNotReportedAsACredentialProblem() {
        when(client.catalogue()).thenThrow(new OpenRouterException(403, "forbidden"));
        refreshJob.refresh();

        assertThat(service.catalogue().lastError())
                .isEqualTo("BLOCKED (HTTP 403)")
                .doesNotContain("CREDENTIAL");
    }

    /**
     * A sustained failure has to leave this method, or nothing outside the
     * database ever learns about it.
     *
     * <p>Catching everything and writing a column is right for one bad hour and
     * wrong for a broken week: a job that returns normally looks healthy, and
     * the row is read by a screen nobody must open. The host health check counts
     * FAILED JobRunr rows every ten minutes, so throwing is what makes a
     * permanently empty picker visible to somebody.
     */
    @Test
    void aSustainedFailureIsAllowedOutOfTheJob() {
        when(client.catalogue()).thenThrow(new OpenRouterException(503, "down"));

        // The first two stay quiet: one vendor blip an hour apart is ordinary.
        refreshJob.refresh();
        refreshJob.refresh();
        assertThat(service.catalogue().consecutiveFailures()).isEqualTo(2);

        assertThatThrownBy(() -> refreshJob.refresh())
                .describedAs("the third in a row must escape so a watched counter moves")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3 times in a row");
        assertThat(service.catalogue().consecutiveFailures()).isEqualTo(3);
    }

    /** A success clears the count, so a recovered vendor stops raising. */
    @Test
    void aSuccessClearsTheFailureCount() {
        when(client.catalogue()).thenThrow(new OpenRouterException(503, "down"));
        refreshJob.refresh();
        refreshJob.refresh();

        // doReturn, not when(...): the mock is already stubbed to throw, and
        // when(client.catalogue()) would call it during stubbing.
        doReturn(List.of(model("openai/gpt-4o-mini", "0.0000006"))).when(client).catalogue();
        refreshJob.refresh();

        assertThat(service.catalogue().consecutiveFailures()).isZero();
        assertThat(service.catalogue().lastError()).isNull();
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
