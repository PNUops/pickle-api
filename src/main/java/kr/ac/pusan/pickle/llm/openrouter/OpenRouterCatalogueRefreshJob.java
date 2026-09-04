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
    static final String INTERVAL_ISO = "PT1H";

    /**
     * Parsed from the same string the annotation uses. Two literals would let
     * someone tune the schedule and silently move the staleness threshold with
     * it, or not move it — which is what the comment on the threshold claims
     * cannot happen.
     */
    private static final Duration INTERVAL = Duration.parse(INTERVAL_ISO);

    /**
     * Consecutive failures before the job stops swallowing them. Three at an
     * hourly cadence is three hours, which is also when the catalogue turns
     * STALE — the screen and the alarm start saying something at the same time.
     */
    private static final int ESCALATE_AFTER = 3;

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

    @Recurring(id = JOB_ID, interval = INTERVAL_ISO)
    @Job(name = JOB_ID, retries = 0)
    public void refresh() {
        Instant now = Instant.now(clock);
        try {
            List<OpenRouterClient.VendorModel> models = client.catalogue();
            // Inside the try on purpose. The write can fail on its own — a
            // constraint the vendor's data violates, a column that will not
            // hold a value — and if it escapes, nothing records the failure:
            // the state row stays untouched and the screen says "not fetched
            // yet", which is the message a brand-new deployment shows. A
            // refresh that fails forever must not look like one that has not
            // run yet.
            catalogue.replaceListing(models, now);
            log.info("OpenRouter catalogue refreshed with {} models", models.size());
        } catch (OpenRouterException e) {
            // The vendor's own words are not stored: the column is shown to an
            // administrator and a vendor body can carry anything.
            String reason = describe(e);
            log.warn("OpenRouter catalogue refresh failed: {}", reason);
            escalate(catalogue.recordFailure(reason, now), reason);
        } catch (RuntimeException e) {
            log.warn("OpenRouter catalogue refresh failed unexpectedly", e);
            escalate(catalogue.recordFailure("UNEXPECTED", now), "UNEXPECTED");
        }
    }

    /**
     * Lets a sustained failure out of this method, so that something already
     * watched notices.
     *
     * <p>Catching everything and recording it in a column is the right answer
     * for one bad hour and the wrong one for a broken week: the row is read by
     * a screen nobody is required to open, while a job that returns normally is
     * indistinguishable from a healthy one. The host health check counts
     * {@code FAILED} JobRunr rows every ten minutes, so throwing is what turns
     * "the picker is empty and nobody knows why" into a line somebody already
     * reads.
     *
     * <p>It throws on the third consecutive failure rather than the first
     * because a single vendor blip is ordinary and should not raise anything;
     * three hours of them is not. The state row keeps the whole history either
     * way, so nothing is lost by staying quiet at first.
     */
    private static void escalate(int consecutiveFailures, String reason) {
        if (consecutiveFailures >= ESCALATE_AFTER) {
            throw new IllegalStateException("OpenRouter catalogue refresh has failed "
                    + consecutiveFailures + " times in a row: " + reason);
        }
    }

    /**
     * The shared classifier is not used here, and the reason is that it reads
     * 401 and 403 as {@code CREDENTIAL_ERROR}. This call carries no credential,
     * so that bucket cannot be true of it: a refusal at those statuses is an
     * address or region block, and telling an administrator their credential is
     * wrong would send them to rotate a key that is not involved.
     */
    private static String describe(OpenRouterException e) {
        int status = e.status();
        String bucket;
        if (status == 0) {
            bucket = "UNREACHABLE";
        } else if (status == 429) {
            bucket = "THROTTLED";
        } else if (status >= 500) {
            bucket = "VENDOR_UNAVAILABLE";
        } else if (status == 401 || status == 403) {
            bucket = "BLOCKED";
        } else {
            bucket = "VENDOR_REJECTED";
        }
        return bucket + " (HTTP " + status + ")";
    }

    /** The cadence, exposed so the freshness threshold is derived rather than repeated. */
    public static Duration interval() {
        return INTERVAL;
    }
}
