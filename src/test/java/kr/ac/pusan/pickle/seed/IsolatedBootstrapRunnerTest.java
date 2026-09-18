package kr.ac.pusan.pickle.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(MockitoExtension.class)
class IsolatedBootstrapRunnerTest {

    @Mock
    private BootstrapAdminService bootstrapAdminService;
    @Test
    void normalIsolatedProfileDoesNotLoadEitherSeeder() {
        assertThat(DevDataSeeder.class.getAnnotation(Profile.class).value())
                .containsExactly("dev", "test");
        assertThat(ProdBootstrapSeeder.class.getAnnotation(Profile.class).value())
                .containsExactly("staging", "prod");
        assertThat(IsolatedBootstrapRunner.class.getAnnotation(Profile.class).value())
                .containsExactly("isolated-bootstrap");
    }

    @Test
    void requiresExplicitOptIn() {
        MockEnvironment environment = environment();
        IsolatedBootstrapRunner runner = new IsolatedBootstrapRunner(
                bootstrapAdminService, environment, false);

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PICKLE_ISOLATED_BOOTSTRAP_ENABLED");
        verify(bootstrapAdminService, never()).bootstrapIsolated(null, null);
    }

    @Test
    void rejectsDevEvenWhenIsolatedProfilesAreAlsoPresent() {
        MockEnvironment environment = environment();
        environment.addActiveProfile("dev");
        IsolatedBootstrapRunner runner = new IsolatedBootstrapRunner(
                bootstrapAdminService, environment, true);

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("프로파일");
        verify(bootstrapAdminService, never()).bootstrapIsolated(null, null);
    }

    @Test
    void passesProtectedEnvironmentValuesToOneShotService() {
        MockEnvironment environment = environment()
                .withProperty(ProdBootstrapSeeder.EMAIL_ENV, "admin@example.test")
                .withProperty(ProdBootstrapSeeder.PASSWORD_ENV, "S3cure-Bootstrap-Pw!");
        IsolatedBootstrapRunner runner = new IsolatedBootstrapRunner(
                bootstrapAdminService, environment, true);

        runner.run(null);

        verify(bootstrapAdminService).bootstrapIsolated(
                "admin@example.test", "S3cure-Bootstrap-Pw!");
    }

    private static MockEnvironment environment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("isolated", "isolated-bootstrap");
        return environment;
    }
}
