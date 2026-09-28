package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The direct scheduler must remain absent until a deployment explicitly opts in. */
class LlmDirectQuotaSchedulerConditionTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(LlmQuotaService.class, () -> mock(LlmQuotaService.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(LlmDirectQuotaScheduler.class);

    @Test
    void defaultAndExplicitFalseDoNotCreateTheScheduler() {
        contexts.run(context -> assertThat(context).doesNotHaveBean(LlmDirectQuotaScheduler.class));
        contexts.withPropertyValues("pickle.llm.quota-direct.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(LlmDirectQuotaScheduler.class));
    }

    @Test
    void exactOptInCreatesTheScheduler() {
        contexts.withPropertyValues("pickle.llm.quota-direct.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(LlmDirectQuotaScheduler.class));
    }
}
