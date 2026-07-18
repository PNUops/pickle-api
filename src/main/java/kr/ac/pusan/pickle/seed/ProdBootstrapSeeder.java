package kr.ac.pusan.pickle.seed;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import kr.ac.pusan.pickle.auth.PasswordPolicy;
import kr.ac.pusan.pickle.group.PersonalGroupService;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production first-run bootstrap: creates the single initial SYS_ADMIN so a
 * fresh prod deploy has a way in, without ever shipping a password in git (unlike
 * {@link DevDataSeeder}, which is dev/test only). The admin's credentials come
 * from {@code PICKLE_BOOTSTRAP_ADMIN_EMAIL} / {@code PICKLE_BOOTSTRAP_ADMIN_PASSWORD}
 * (/etc/pickle/api.env).
 *
 * <p><b>Fail-fast</b>: if either env var is missing/blank, or the password is an
 * obvious placeholder (or too short), startup is aborted with a clear Korean log
 * — a prod instance never comes up with a guessable admin. <b>Idempotent</b>: if
 * any SYS_ADMIN already exists the run is a no-op, so it seeds exactly once and
 * re-deploys do nothing. It never creates orgs, org admins, or any demo data.</p>
 */
@Component
@Profile("prod")
public class ProdBootstrapSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProdBootstrapSeeder.class);

    static final String EMAIL_ENV = "PICKLE_BOOTSTRAP_ADMIN_EMAIL";
    static final String PASSWORD_ENV = "PICKLE_BOOTSTRAP_ADMIN_PASSWORD";
    private static final int MIN_PASSWORD_LENGTH = 12;

    /** Obvious placeholders (incl. the dev seed defaults) refused outright, case-insensitive. */
    private static final Set<String> FORBIDDEN_PASSWORDS = Set.of(
            "changeme", "change-me", "password", "passw0rd", "admin", "administrator",
            "secret", "pickle", "pickle-sysadmin-dev!", "pickle-orgadmin-dev!",
            "12345678", "123456789", "qwerty", "letmein");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PersonalGroupService personalGroupService;
    private final PasswordPolicy passwordPolicy;
    private final Environment environment;

    public ProdBootstrapSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder,
            PersonalGroupService personalGroupService, PasswordPolicy passwordPolicy,
            Environment environment) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.personalGroupService = personalGroupService;
        this.passwordPolicy = passwordPolicy;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String email = trimmed(environment.getProperty(EMAIL_ENV));
        String password = environment.getProperty(PASSWORD_ENV);
        validate(email, password);

        if (!userRepository.findByRole(UserRole.SYS_ADMIN).isEmpty()) {
            log.info("SYS_ADMIN 계정이 이미 존재하여 부트스트랩 시딩을 건너뜁니다.");
            return;
        }
        if (userRepository.existsByEmail(email)) {
            // A non-SYS_ADMIN already holds the bootstrap email — refuse rather
            // than silently reusing or clobbering an existing account.
            throw new IllegalStateException(
                    EMAIL_ENV + " 이메일이 이미 다른 계정에 사용 중입니다. 다른 이메일을 지정하세요.");
        }

        log.info("초기 SYS_ADMIN 계정을 부트스트랩합니다: {}", email);
        User admin = new User(email, passwordEncoder.encode(password), "시스템 관리자");
        admin.setRole(UserRole.SYS_ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        admin.setEmailVerifiedAt(Instant.now());
        admin = userRepository.save(admin);
        personalGroupService.ensurePersonalGroup(admin);
    }

    private void validate(String email, String password) {
        if (email == null || email.isBlank()) {
            throw new IllegalStateException(EMAIL_ENV
                    + " 환경 변수가 비어 있습니다. 초기 관리자 이메일을 설정한 뒤 다시 시작하세요.");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(PASSWORD_ENV
                    + " 환경 변수가 비어 있습니다. 초기 관리자 비밀번호를 설정한 뒤 다시 시작하세요.");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(PASSWORD_ENV + " 비밀번호가 너무 짧습니다("
                    + MIN_PASSWORD_LENGTH + "자 이상 필요). 더 강력한 비밀번호로 다시 시작하세요.");
        }
        if (FORBIDDEN_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(PASSWORD_ENV
                    + " 비밀번호가 추측 가능한 기본값입니다. 실제 비밀번호로 교체한 뒤 다시 시작하세요.");
        }
        // Same weak-password bar every self-service account clears: a long but
        // structurally weak bootstrap password (e.g. all-same-char) must not slip
        // through the length floor + blacklist. PasswordPolicy throws 422 on a
        // weak value; wrap it so startup fails fast with the ops-facing message.
        try {
            passwordPolicy.validate(password, email);
        } catch (RuntimeException weak) {
            throw new IllegalStateException(PASSWORD_ENV
                    + " 비밀번호가 보안 정책을 통과하지 못했습니다. 더 강력한 비밀번호로 다시 시작하세요.", weak);
        }
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }
}
