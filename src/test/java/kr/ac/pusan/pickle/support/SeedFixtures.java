package kr.ac.pusan.pickle.support;

import java.util.UUID;
import kr.ac.pusan.pickle.seed.DevDataSeeder;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Single reference point for the dev/test seed data most integration tests
 * lean on (the org row and the two seed accounts), so the seed's identity
 * lives in one place instead of dozens of string literals. Values mirror
 * {@link DevDataSeeder} and the {@code pickle.seed} defaults in
 * {@code application.yml}.
 */
public final class SeedFixtures {

    /** Proxmox template the seeded catalog row clones from. */
    public static final int TEMPLATE_VMID = DevDataSeeder.SEED_TEMPLATE_VMID;

    /** Seed org display name ({@link DevDataSeeder#ORG_NAME}); the org is seeded hidden. */
    public static final String ORG_NAME = DevDataSeeder.ORG_NAME;

    /** Default {@code pickle.seed.orgadmin-email} (no env override in tests). */
    public static final String ORGADMIN_EMAIL = "orgadmin@pickle.local";

    /** Default {@code pickle.seed.sysadmin-email} (no env override in tests). */
    public static final String SYSADMIN_EMAIL = "admin@pickle.local";

    /**
     * A well-formed identifier no row has, for the "does not exist" probes that
     * used to pass a number like 999999. One shared value, so every such probe
     * means the same thing wherever it appears.
     */
    public static final UUID UNKNOWN_ID = UUID.fromString("00000000-0000-4000-8000-0000deadbeef");

    private SeedFixtures() {
    }

    /** Id of the seeded org ({@code queryForObject} throws if the seed is missing). */
    public static long seedOrgId(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForObject(
                "select id from orgs where name = '" + ORG_NAME + "'", Long.class);
    }

    /** Public identifier of the seeded org. */
    public static UUID seedOrgPublicId(JdbcTemplate jdbcTemplate) {
        return publicId(jdbcTemplate, "orgs", seedOrgId(jdbcTemplate));
    }

    /**
     * The public identifier of a row a fixture created through direct SQL and
     * now has to name over the API — internal ids stay the setup's business,
     * public ids are what the endpoints take. {@code table} is always a literal
     * from the test, never anything a request supplied.
     */
    public static UUID publicId(JdbcTemplate jdbcTemplate, String table, long id) {
        return jdbcTemplate.queryForObject(
                "select public_id from " + table + " where id = ?", UUID.class, id);
    }

    /**
     * The internal id behind a public one — for a fixture that created its row
     * through the API and now has to set the rest up with direct SQL.
     */
    public static long internalId(JdbcTemplate jdbcTemplate, String table, UUID publicId) {
        return jdbcTemplate.queryForObject(
                "select id from " + table + " where public_id = ?", Long.class, publicId);
    }

    /** Id of the seeded ORG_ADMIN account. */
    public static long orgadminId(JdbcTemplate jdbcTemplate) {
        return userId(jdbcTemplate, ORGADMIN_EMAIL);
    }

    /** Id of the seeded SYS_ADMIN account. */
    public static long sysadminId(JdbcTemplate jdbcTemplate) {
        return userId(jdbcTemplate, SYSADMIN_EMAIL);
    }

    /**
     * Makes an account administer an org. V90 replaced {@code users.org_id}
     * with the {@code user_org_roles} join table, so a fixture that used to set
     * one column now writes a row. Idempotent, and a null org is a no-op so the
     * callers that pass one through stay as they were.
     */
    public static void grantOrgRole(JdbcTemplate jdbcTemplate, Long userId, Long orgId,
            UserRole role) {
        if (orgId == null) {
            return;
        }
        jdbcTemplate.update("insert into user_org_roles (user_id, org_id, role)"
                + " values (?, ?, ?::user_role)"
                + " on conflict (user_id, org_id) do update set role = excluded.role",
                userId, orgId, role.name());
    }

    /** The orgs an account administers, for asserting a grant or a revoke. */
    public static java.util.List<Long> managedOrgIds(JdbcTemplate jdbcTemplate, Long userId) {
        return jdbcTemplate.queryForList(
                "select org_id from user_org_roles where user_id = ? order by org_id",
                Long.class, userId);
    }

    private static long userId(JdbcTemplate jdbcTemplate, String email) {
        return jdbcTemplate.queryForObject(
                "select id from users where email = '" + email + "'", Long.class);
    }
}
