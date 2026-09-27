package kr.ac.pusan.pickle.workspace;

import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Transaction-scoped advisory locks keyed on the identifier an invitation
 * names, taken by both the invitation and the claim side.
 *
 * <p>Without them the two sides can miss each other: an owner's request reads
 * the account as not yet ACTIVE, the activation commits and finds nothing to
 * claim, and then the owner's invitation commits and waits for a trigger that
 * has already fired. Holding the same lock makes whichever side comes second
 * see what the first committed. Neither the user row nor the invitation rows
 * can serve: Google registration creates and activates the account in one
 * transaction, and the invitation row does not exist yet.</p>
 *
 * <p>Callers take several of these in ascending key order (emails before
 * 학번, each sorted). That alone does not rule out a cycle, because the owner's
 * side also inserts memberships and the membership unique index is a second
 * resource both sides touch; {@code InvitationClaimService} explains the
 * extra rule (a 학번 claim takes the account's email lock first) that closes
 * it.</p>
 */
@Component
public class InvitationLocks {

    private final JdbcTemplate jdbcTemplate;

    public InvitationLocks(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    static String emailKey(String email) {
        return "e:" + email.toLowerCase(Locale.ROOT);
    }

    static String studentNoKey(String studentNo) {
        return "s:" + studentNo.toUpperCase(Locale.ROOT);
    }

    /** Blocks until this transaction holds the lock for {@code key}; released at commit or rollback. */
    void lock(String key) {
        jdbcTemplate.queryForList("select pg_advisory_xact_lock(hashtext(?))", "workspace_invitation|" + key);
    }
}
