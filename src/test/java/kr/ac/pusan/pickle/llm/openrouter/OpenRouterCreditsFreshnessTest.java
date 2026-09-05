package kr.ac.pusan.pickle.llm.openrouter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Two screens read this mapping through one method, and the tests that exercise
 * it end to end only ever pick times far from the boundary — a window of three
 * hours sampled at nine hours and at now. Either comparison could be inverted
 * at the edge and both would stay green, so the edge is pinned here.
 */
class OpenRouterCreditsFreshnessTest {

    private static final Instant OBSERVED = Instant.parse("2026-09-04T12:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(3);

    private static OpenRouterCreditsFreshness at(Instant now) {
        return OpenRouterCreditsFreshness.of(OBSERVED, WINDOW,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    /**
     * The window is exclusive at its far end: an observation exactly as old as
     * the window is already stale. Which side the boundary falls on is a real
     * choice rather than an accident, so it is stated once here instead of
     * being re-derived from whichever comparison a reader happens to open.
     */
    @Test
    void theWindowIsExclusiveAtItsFarEnd() {
        assertThat(at(OBSERVED.plus(WINDOW).minusMillis(1)))
                .isEqualTo(OpenRouterCreditsFreshness.FRESH);
        assertThat(at(OBSERVED.plus(WINDOW)))
                .isEqualTo(OpenRouterCreditsFreshness.STALE);
        assertThat(at(OBSERVED.plus(WINDOW).plusMillis(1)))
                .isEqualTo(OpenRouterCreditsFreshness.STALE);
    }

    @Test
    void anObservationJustTakenIsFresh() {
        assertThat(at(OBSERVED)).isEqualTo(OpenRouterCreditsFreshness.FRESH);
    }

    /**
     * A clock that moved backwards puts the observation in the future. That is
     * a broken clock rather than an old reading, and calling it stale would put
     * a caveat on the screen that describes the server rather than the data.
     */
    @Test
    void anObservationInTheFutureIsFreshRatherThanStale() {
        assertThat(at(OBSERVED.minusSeconds(1)))
                .isEqualTo(OpenRouterCreditsFreshness.FRESH);
        assertThat(at(OBSERVED.minus(Duration.ofDays(400))))
                .isEqualTo(OpenRouterCreditsFreshness.FRESH);
    }

    /** Never observed is not the same as observed long ago. */
    @Test
    void neverObservedIsItsOwnState() {
        assertThat(OpenRouterCreditsFreshness.of(null, WINDOW,
                Clock.fixed(OBSERVED, ZoneOffset.UTC)))
                .isEqualTo(OpenRouterCreditsFreshness.UNKNOWN);
    }

    /**
     * The window belongs to the caller, not to this type. The same observation
     * reads differently under the credits window and the catalogue window, and
     * that is the reason the parameter exists.
     */
    @Test
    void theSameObservationReadsDifferentlyUnderDifferentWindows() {
        Instant now = OBSERVED.plus(Duration.ofHours(1));
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        assertThat(OpenRouterCreditsFreshness.of(OBSERVED, Duration.ofMinutes(30), clock))
                .isEqualTo(OpenRouterCreditsFreshness.STALE);
        assertThat(OpenRouterCreditsFreshness.of(OBSERVED, Duration.ofHours(3), clock))
                .isEqualTo(OpenRouterCreditsFreshness.FRESH);
    }
}
