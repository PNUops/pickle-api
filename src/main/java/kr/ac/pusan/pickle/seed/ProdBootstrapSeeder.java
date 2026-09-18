package kr.ac.pusan.pickle.seed;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * First-run bootstrap for the operational profiles: creates the single initial
 * SYS_ADMIN so a fresh deploy has a way in, without ever shipping a password in
 * git (unlike {@link DevDataSeeder}, which is dev/test only). It runs on
 * {@code staging} as well as {@code prod} — the two share an SMTP sender, an
 * admin-2FA default and a separate database each, and a staging instance with no
 * seeder comes up with zero users and no way to create the first one. The
 * admin's credentials come
 * from {@code PICKLE_BOOTSTRAP_ADMIN_EMAIL} / {@code PICKLE_BOOTSTRAP_ADMIN_PASSWORD}
 * (/etc/pickle/api.env).
 *
 * <p><b>Fail-fast</b>: if either env var is missing/blank, or the password is an
 * obvious placeholder (or too short), startup is aborted with a clear Korean log
 * — an instance on these profiles never comes up with a guessable admin. <b>Idempotent</b>: if
 * any SYS_ADMIN already exists the run is a no-op, so it seeds exactly once and
 * re-deploys do nothing. It never creates orgs, org admins, or any demo data.</p>
 */
@Component
@Profile({"staging", "prod"})
public class ProdBootstrapSeeder implements ApplicationRunner {

    static final String EMAIL_ENV = "PICKLE_BOOTSTRAP_ADMIN_EMAIL";
    static final String PASSWORD_ENV = "PICKLE_BOOTSTRAP_ADMIN_PASSWORD";
    private final BootstrapAdminService bootstrapAdminService;
    private final Environment environment;

    public ProdBootstrapSeeder(BootstrapAdminService bootstrapAdminService, Environment environment) {
        this.bootstrapAdminService = bootstrapAdminService;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrapAdminService.bootstrapOperational(environment.getProperty(EMAIL_ENV),
                environment.getProperty(PASSWORD_ENV));
    }
}
