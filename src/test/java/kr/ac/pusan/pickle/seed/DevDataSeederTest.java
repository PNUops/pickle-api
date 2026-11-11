package kr.ac.pusan.pickle.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @Test
    @Transactional
    void refusesAnExistingDatabaseWithoutTheDevelopmentMarker() {
        jdbcTemplate.update("delete from settings where key = 'dev_environment_marker'");

        assertThatThrownBy(() -> seeder.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("development marker");
    }

    @Test
    @Transactional
    void emptySettingsReuseCurrentHelpWithoutAddingOtherKnownKeys() {
        jdbcTemplate.update("delete from settings");

        seeder.run(null);

        assertThat(jdbcTemplate.queryForObject("select count(*) from settings", Integer.class))
                .isEqualTo(27);
        assertThat(jdbcTemplate.queryForObject("""
                select description from settings where key = 'vm_delete_grace_hours'
                """, String.class)).isEqualTo(
                        "본인 삭제를 접수한 뒤 파기까지 기다릴 시간이에요(시간). 관리자만 이 유예 기간에 취소할 수 있어요.");
        assertThat(jdbcTemplate.queryForObject("""
                select value::text from settings where key = 'vm_delete_grace_hours'
                """, String.class)).isEqualTo("168");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from settings where key in (
                    'bulk_provision_concurrency', 'custom_domain_limit_per_hour',
                    'domain_renewal_days', 'domain_renewal_notice_days', 'llm_usage_retention_days',
                    'platform_subdomain_reserve_days', 'platform_subdomains_per_vm')
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select description from settings where key = 'dev_environment_marker'
                """, String.class)).isEqualTo("개발 또는 테스트 환경으로 새로 만든 DB의 시드 확인 표시.");
        assertThat(jdbcTemplate.queryForObject("""
                select value::text from settings where key = 'dev_environment_marker'
                """, String.class)).isEqualTo("true");
    }
}
