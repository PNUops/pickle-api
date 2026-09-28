package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/** The direct rollup must not exist in an ordinary API process. */
class LlmDirectUsageRollupSchedulerConditionTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withBean(LlmUsageRollupService.class, () -> mock(LlmUsageRollupService.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(LlmDirectUsageRollupScheduler.class);

    @Test
    void defaultAndExplicitFalseDoNotCreateTheScheduler() {
        contexts.run(context -> assertThat(context)
                .doesNotHaveBean(LlmDirectUsageRollupScheduler.class));
        contexts.withPropertyValues("pickle.llm.usage-rollup-direct.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(LlmDirectUsageRollupScheduler.class));
    }

    @Test
    void exactOptInCreatesTheScheduler() {
        contexts.withPropertyValues("pickle.llm.usage-rollup-direct.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(LlmDirectUsageRollupScheduler.class));
    }
}
