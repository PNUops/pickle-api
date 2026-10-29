package kr.ac.pusan.pickle.llm;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import kr.ac.pusan.pickle.common.error.ApiException;

/**
 * Per-process fixed-window counter for the async-object ownership calls.
 *
 * <p>The other gateway buckets go through {@link kr.ac.pusan.pickle.auth.RateLimitService},
 * a separate transaction that upserts one row per call. That is fine at a
 * poll every few seconds; lookup sits on a client's request path at tens of
 * calls a second, and every call would queue on the same row lock. These
 * buckets only protect the api from a runaway peer, which a counter in this
 * process does equally well — the gateway's per-key limits bound what clients
 * can send in the first place.</p>
 */
final class LlmObjectRateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    private final LongSupplier nowMillis;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    LlmObjectRateLimiter() {
        this(System::currentTimeMillis);
    }

    LlmObjectRateLimiter(LongSupplier nowMillis) {
        this.nowMillis = nowMillis;
    }

    /**
     * Counts one call against {@code scope}; throws the same 429 the database
     * buckets throw once the minute's budget is spent.
     */
    void hit(String scope, int limitPerMinute) {
        long now = nowMillis.getAsLong();
        long start = now - Math.floorMod(now, WINDOW_MILLIS);
        Window window = windows.compute(scope, (key, current) ->
                current != null && current.start == start
                        ? new Window(start, current.count + 1)
                        : new Window(start, 1));
        if (window.count > limitPerMinute) {
            long retryAfter = Math.max(1, (start + WINDOW_MILLIS - now + 999) / 1000);
            throw ApiException.rateLimited(retryAfter);
        }
    }

    private record Window(long start, int count) {
    }
}
