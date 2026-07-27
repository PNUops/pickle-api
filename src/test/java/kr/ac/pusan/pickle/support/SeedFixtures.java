package kr.ac.pusan.pickle.support;

import kr.ac.pusan.pickle.seed.DevDataSeeder;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Single reference point for the dev/test seed data most integration tests
 * lean on (the org row and the two seed accounts), so the seed's identity
 * lives in one place instead of dozens of string literals. Values mirror
 * {@link DevDataSeeder} and the {@code pickle.seed} defaults in
 * {@code application.yml}.
 */
public final class SeedFixtures {

    /** Seed org slug ({@link DevDataSeeder#ORG_SLUG}); the org is seeded hidden. */
    public static final String ORG_SLUG = DevDataSeeder.ORG_SLUG;

    /** Seed org display name ({@link DevDataSeeder#ORG_NAME}). */
    public static final String ORG_NAME = DevDataSeeder.ORG_NAME;

    /** Default {@code pickle.seed.orgadmin-email} (no env override in tests). */
    public static final String ORGADMIN_EMAIL = "orgadmin@pickle.local";

    /** Default {@code pickle.seed.sysadmin-email} (no env override in tests). */
    public static final String SYSADMIN_EMAIL = "admin@pickle.local";

    private SeedFixtures() {
    }

    /** Id of the seeded org ({@code queryForObject} throws if the seed is missing). */
    public static long seedOrgId(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForObject(
                "select id from orgs where slug = '" + ORG_SLUG + "'", Long.class);
    }

    /** Id of the seeded ORG_ADMIN account. */
    public static long orgadminId(JdbcTemplate jdbcTemplate) {
        return userId(jdbcTemplate, ORGADMIN_EMAIL);
    }

    /** Id of the seeded SYS_ADMIN account. */
    public static long sysadminId(JdbcTemplate jdbcTemplate) {
        return userId(jdbcTemplate, SYSADMIN_EMAIL);
    }

    private static long userId(JdbcTemplate jdbcTemplate, String email) {
        return jdbcTemplate.queryForObject(
                "select id from users where email = '" + email + "'", Long.class);
    }
}
