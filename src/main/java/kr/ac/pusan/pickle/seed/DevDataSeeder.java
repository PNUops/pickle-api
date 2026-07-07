package kr.ac.pusan.pickle.seed;

import java.time.Instant;
import kr.ac.pusan.pickle.group.PersonalGroupService;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent dev/test seed (insert-if-absent by email/slug): SYS_ADMIN, one
 * org (SW교육센터/sw-edu) and its ORG_ADMIN. Runs at startup instead of a
 * migration so no password hash lands in git (docs/plan/02, 07). Seed accounts
 * are pre-verified (they bypass the @pusan.ac.kr self-signup restriction).
 */
@Component
@Profile({"dev", "test"})
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    static final String ORG_NAME = "SW교육센터";
    static final String ORG_SLUG = "sw-edu";

    private final UserRepository userRepository;
    private final OrgRepository orgRepository;
    private final PersonalGroupService personalGroupService;
    private final PasswordEncoder passwordEncoder;
    private final SeedProperties properties;

    public DevDataSeeder(UserRepository userRepository, OrgRepository orgRepository,
            PersonalGroupService personalGroupService, PasswordEncoder passwordEncoder,
            SeedProperties properties) {
        this.userRepository = userRepository;
        this.orgRepository = orgRepository;
        this.personalGroupService = personalGroupService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedUser(properties.sysadminEmail(), properties.sysadminPassword(), "시스템 관리자",
                UserRole.SYS_ADMIN, null);

        Org org = orgRepository.findBySlug(ORG_SLUG)
                .orElseGet(() -> {
                    log.info("Seeding org '{}' ({})", ORG_NAME, ORG_SLUG);
                    return orgRepository.save(new Org(ORG_NAME, ORG_SLUG, "SW교육센터 (개발용 시드 기관)"));
                });

        seedUser(properties.orgadminEmail(), properties.orgadminPassword(), "기관 관리자",
                UserRole.ORG_ADMIN, org.getId());
    }

    private void seedUser(String email, String password, String name, UserRole role, Long orgId) {
        userRepository.findByEmail(email).orElseGet(() -> {
            log.info("Seeding {} account {}", role, email);
            User user = new User(email, passwordEncoder.encode(password), name);
            user.setRole(role);
            user.setOrgId(orgId);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            user = userRepository.save(user);
            personalGroupService.ensurePersonalGroup(user);
            return user;
        });
    }
}
