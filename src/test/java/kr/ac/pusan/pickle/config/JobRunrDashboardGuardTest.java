package kr.ac.pusan.pickle.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The dashboard may not run without basic-auth credentials (JobRunr is fail-open). */
class JobRunrDashboardGuardTest {

    @Test
    void enabledWithoutCredentialsFailsStartup() {
        assertThatThrownBy(() -> new JobRunrDashboardGuard(true, "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PICKLE_JOBRUNR_DASH_USER");
        assertThatThrownBy(() -> new JobRunrDashboardGuard(true, "ops", " "))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JobRunrDashboardGuard(true, null, "secret"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void disabledOrFullyConfiguredStartsNormally() {
        assertThatCode(() -> new JobRunrDashboardGuard(false, "", "")).doesNotThrowAnyException();
        assertThatCode(() -> new JobRunrDashboardGuard(true, "ops", "secret"))
                .doesNotThrowAnyException();
    }
}
