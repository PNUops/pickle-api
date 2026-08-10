package kr.ac.pusan.pickle.seed;

import static org.assertj.core.api.Assertions.assertThat;

import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Seed accounts follow the configured PICKLE_SEED_* password: a stale hash is
 * re-encoded at startup (rotation via env), a matching hash is left untouched
 * (no gratuitous re-encode churn).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class DevDataSeederTest {

    @Autowired
    private DevDataSeeder seeder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgRepository orgRepository;

    @Autowired
    private SeedProperties properties;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void staleHashIsReencodedToConfiguredPassword() throws Exception {
        User admin = userRepository.findByEmail(properties.sysadminEmail()).orElseThrow();
        admin.setPasswordHash(passwordEncoder.encode("rotated-away-old-password"));
        userRepository.save(admin);

        seeder.run(null);

        User after = userRepository.findByEmail(properties.sysadminEmail()).orElseThrow();
        assertThat(passwordEncoder.matches(properties.sysadminPassword(), after.getPasswordHash()))
                .isTrue();
    }

    @Test
    void matchingHashIsNotReencoded() throws Exception {
        seeder.run(null); // ensure hash already matches the configured password
        String before = userRepository.findByEmail(properties.orgadminEmail())
                .orElseThrow().getPasswordHash();

        seeder.run(null);

        String after = userRepository.findByEmail(properties.orgadminEmail())
                .orElseThrow().getPasswordHash();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void seedOrgIsNeutralAndHidden() {
        Org org = orgRepository.findFirstByNameOrderByIdAsc(DevDataSeeder.ORG_NAME).orElseThrow();
        assertThat(org.getName()).isEqualTo(DevDataSeeder.ORG_NAME);
        assertThat(org.isHidden()).isTrue();
    }
}
