package kr.ac.pusan.pickle.networkpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.recoverypolicy.RecoveryPolicyApplication;
import org.flywaydb.core.Flyway;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

/** Only the explicit JDBC and PVE policy beans may start in recovery mode. */
@SpringBootTest(classes = RecoveryPolicyApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(EmbeddedPostgresConfig.class)
class RecoveryPolicyApplicationTest {

    @Autowired ApplicationContext context;

    @Test
    void bootsNoUnrelatedProducerOrSchemaWriter() {
        assertThat(context.getBeansOfType(RecoveryPolicyCommand.class)).hasSize(1);
        assertThat(context.getBeansOfType(RecoveryPolicyDb.class)).hasSize(1);
        assertThat(context.getBeansOfType(VmNetworkPolicyApplyJob.class)).isEmpty();
        assertThat(context.getBeansOfType(JobScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(ApplicationRunner.class)).isEmpty();
        assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
        assertThat(context.getBeansOfType(ServletWebServerFactory.class)).isEmpty();
    }

    @Test
    void rejectsMissingEvidencePinsBeforeBoot() {
        assertThatThrownBy(() -> RecoveryPolicyMain.parse(new String[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecoveryPolicyMain.parse(new String[] {
                "/manifest", "bad-sha", "/proof", "bad-sha", "/identity",
                UUID.randomUUID().toString()})).isInstanceOf(IllegalArgumentException.class);
        assertThat(RecoveryPolicyMain.outcomeLabel(
                new VmNetworkPolicyAdvisoryLock.OutcomeUnknownException(
                        "Read-only result: APPLIED_WITH_AUDIT", null)))
                .isEqualTo("APPLIED_WITH_AUDIT");
        assertThat(RecoveryPolicyMain.outcomeLabel(
                new VmNetworkPolicyAdvisoryLock.OutcomeUnknownException(
                        "credential or provider detail", null)))
                .isEqualTo("UNAVAILABLE");
    }
}
