package kr.ac.pusan.pickle.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes rows into a resource's access list the way the product does, for the
 * many tests that build their VMs straight through {@link JdbcTemplate} and so
 * never pass the approval step that would grant the requester.
 *
 * <p>Reaching a VM is decided by that list alone, so a fixture VM that nobody
 * has been granted is a VM nobody can act on — which is why almost every test
 * that inserts one also has to say who reaches it and at which rung.
 */
public final class AccessGrantFixtures {

    private AccessGrantFixtures() {
    }

    /** Names one person on one VM at {@code role} (re-granting moves the rung). */
    public static void grantVmToUser(JdbcTemplate jdbcTemplate, long vmId, long userId,
            String role) {
        jdbcTemplate.update("""
                insert into resource_access_grants
                       (resource_type, resource_id, grantee_type, user_id, role)
                values ('VM', ?, 'USER', ?, ?::resource_role)
                on conflict (resource_type, resource_id, user_id)
                    where grantee_type = 'USER'
                    do update set role = excluded.role
                """, vmId, userId, role);
    }

    /** Grants the whole owning group one VM at {@code role} (MEMBER or VIEWER). */
    public static void grantVmToOwningGroup(JdbcTemplate jdbcTemplate, long vmId, String role) {
        jdbcTemplate.update("""
                insert into resource_access_grants
                       (resource_type, resource_id, grantee_type, user_id, role)
                values ('VM', ?, 'GROUP', null, ?::resource_role)
                on conflict (resource_type, resource_id)
                    where grantee_type = 'GROUP'
                    do update set role = excluded.role
                """, vmId, role);
    }

    /** Drops every grant on one VM, for tests that assert the ungranted case. */
    public static void revokeVmGrants(JdbcTemplate jdbcTemplate, long vmId) {
        jdbcTemplate.update(
                "delete from resource_access_grants where resource_type = 'VM' and resource_id = ?",
                vmId);
    }
}
