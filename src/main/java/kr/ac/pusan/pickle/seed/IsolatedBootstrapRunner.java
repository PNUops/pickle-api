package kr.ac.pusan.pickle.seed;

import java.util.Arrays;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Explicit one-shot account bootstrap for a newly created isolated database. */
@Component
@Profile("isolated-bootstrap")
public class IsolatedBootstrapRunner implements ApplicationRunner {

    private final BootstrapAdminService bootstrapAdminService;
    private final Environment environment;
    private final boolean enabled;

    public IsolatedBootstrapRunner(BootstrapAdminService bootstrapAdminService,
            Environment environment,
            @Value("${pickle.isolated-bootstrap.enabled:false}") boolean enabled) {
        this.bootstrapAdminService = bootstrapAdminService;
        this.environment = environment;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            throw new IllegalStateException(
                    "격리 관리자 부트스트랩에는 PICKLE_ISOLATED_BOOTSTRAP_ENABLED=true가 필요합니다.");
        }
        Set<String> activeProfiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
        if (!activeProfiles.equals(Set.of("isolated", "isolated-bootstrap"))) {
            throw new IllegalStateException(
                    "격리 관리자 부트스트랩은 isolated,isolated-bootstrap 프로파일만 사용할 수 있습니다.");
        }
        bootstrapAdminService.bootstrapIsolated(
                environment.getProperty(ProdBootstrapSeeder.EMAIL_ENV),
                environment.getProperty(ProdBootstrapSeeder.PASSWORD_ENV));
    }
}
