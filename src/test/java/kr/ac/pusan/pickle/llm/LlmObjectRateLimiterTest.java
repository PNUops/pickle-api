package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import kr.ac.pusan.pickle.common.error.ApiException;
import org.junit.jupiter.api.Test;

class LlmObjectRateLimiterTest {

    private final AtomicLong now = new AtomicLong(120_000L);
    private final LlmObjectRateLimiter limiter = new LlmObjectRateLimiter(now::get);

    @Test
    void refusesPastTheBudgetUntilTheNextWindow() {
        limiter.hit("scope", 2);
        limiter.hit("scope", 2);
        now.addAndGet(20_000L);
        assertThatThrownBy(() -> limiter.hit("scope", 2))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(40L));

        now.addAndGet(40_000L);
        limiter.hit("scope", 2);
    }

    @Test
    void scopesAreCountedApart() {
        limiter.hit("lookup", 1);
        limiter.hit("intents", 1);
        assertThatThrownBy(() -> limiter.hit("lookup", 1)).isInstanceOf(ApiException.class);
    }
}
