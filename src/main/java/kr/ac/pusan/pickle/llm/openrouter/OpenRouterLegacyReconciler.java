package kr.ac.pusan.pickle.llm.openrouter;

import java.time.Clock;
import java.time.Instant;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Keeps the hidden global legacy key pool on its original 30-minute cadence. */
@Component
public class OpenRouterLegacyReconciler {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterLegacyReconciler.class);
    // Retains the pre-V98 recurring id so JobRunr updates that durable row
    // from all-scope reconciliation to legacy-only instead of leaving both.
    static final String JOB_ID = OpenRouterReconciler.JOB_ID;

    private final OpenRouterCredentialResolver credentials;
    private final OpenRouterReconciler reconciler;
    private final Clock clock;

    public OpenRouterLegacyReconciler(OpenRouterCredentialResolver credentials,
            OpenRouterReconciler reconciler, Clock clock) {
        this.credentials = credentials;
        this.reconciler = reconciler;
        this.clock = clock;
    }

    @Recurring(id = JOB_ID, interval = "PT30M")
    @Job(name = JOB_ID, retries = 0)
    public void reconcile() {
        credentials.legacyAccess().ifPresent(access -> {
            try {
                reconciler.reconcileLegacy(access, Instant.now(clock));
            } catch (OpenRouterException error) {
                log.warn("OpenRouter legacy reconcile failed: {}",
                        OpenRouterErrorClassifier.classify(error));
            }
        });
    }
}
