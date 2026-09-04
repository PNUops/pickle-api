package kr.ac.pusan.pickle.llm.openrouter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps the cached vendor model catalogue current.
 *
 * <p>Unlike every other OpenRouter poll this one is not per account. The
 * catalogue is public and identical for everybody, so there is one fetch and
 * one freshness clock; running it through the per-account machinery would
 * multiply a single vendor call by the number of registered accounts for a list
 * that does not vary between them.
 *
 * <p><b>A failure here must never reach an approver as a blocked decision.</b>
 * The list is a convenience over an input that still accepts a typed name, so
 * the job records why it failed and leaves the last good listing in place. What
 * the screen must not do is show an old list as if it were current, which is
 * why the freshness clock only moves on success.
 */
@Component
public class OpenRouterCatalogueRefreshJob {

    static final String JOB_ID = "llm-openrouter-catalogue-refresh";

    /**
     * The vendor serves this list with {@code max-age=300} and it changes a few
     * times a week, so an hour is generous and still well inside the window
     * where a newly released model matters to anybody. Approval does not wait on
     * it: a model the cache has not seen can still be typed.
     */
    private static final Duration INTERVAL = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(OpenRouterCatalogueRefreshJob.class);

    private final OpenRouterClient client;
    private final OpenRouterCatalogueRepository catalogue;
    private final Clock clock;

    public OpenRouterCatalogueRefreshJob(OpenRouterClient client,
            OpenRouterCatalogueRepository catalogue, Clock clock) {
        this.client = client;
        this.catalogue = catalogue;
        this.clock = clock;
    }

    @Recurring(id = JOB_ID, interval = "PT1H")
    @Job(name = JOB_ID, retries = 0)
    public void refresh() {
        Instant now = Instant.now(clock);
        List<OpenRouterClient.VendorModel> models;
        try {
            models = client.catalogue();
        } catch (OpenRouterException e) {
            // The vendor's own words are not stored: the column is shown to an
            // administrator, and a vendor body can carry anything. The status
            // and the classification are enough to tell "they are down" from
            // "we cannot reach them".
            String reason = OpenRouterErrorClassifier.classify(e).name()
                    + " (HTTP " + e.status() + ")";
            catalogue.recordFailure(reason, now);
            log.warn("OpenRouter catalogue refresh failed: {}", reason);
            return;
        } catch (RuntimeException e) {
            catalogue.recordFailure("UNEXPECTED", now);
            log.warn("OpenRouter catalogue refresh failed unexpectedly", e);
            return;
        }
        catalogue.replaceListing(models, now);
        log.info("OpenRouter catalogue refreshed with {} models", models.size());
    }

    /** The cadence, exposed so the freshness threshold is derived rather than repeated. */
    public static Duration interval() {
        return INTERVAL;
    }
}
