package kr.ac.pusan.pickle.llm.openrouter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Server-derived freshness of a cached vendor observation.
 *
 * <p>Named for credits because that was its first use and the name is now in
 * the published contract; the model catalogue reuses it. The three states mean
 * the same thing in both, but <b>the threshold does not</b>: credits go STALE
 * after a fixed 30 minutes, the catalogue after three of its own refresh
 * intervals. Read the threshold from the service that computes it rather than
 * from this type.
 */
public enum OpenRouterCreditsFreshness {
    FRESH,
    STALE,
    UNKNOWN;

    /**
     * The mapping from an observation time to one of these, given the window
     * that observation is allowed to age within.
     *
     * <p>It lives here rather than in each caller because two screens now read
     * the same cache and a second copy of "how old is too old" is a second
     * place to disagree. The window stays with the caller for the reason the
     * class note gives: it differs per observation.
     */
    public static OpenRouterCreditsFreshness of(@Nullable Instant lastSuccessAt,
            Duration staleAfter, Clock clock) {
        if (lastSuccessAt == null) {
            return UNKNOWN;
        }
        Duration age = Duration.between(lastSuccessAt, Instant.now(clock));
        // A negative age (a clock that moved back) reads as fresh rather than
        // stale: the observation is not old, the clock is wrong.
        return age.isNegative() || age.compareTo(staleAfter) < 0 ? FRESH : STALE;
    }
}
