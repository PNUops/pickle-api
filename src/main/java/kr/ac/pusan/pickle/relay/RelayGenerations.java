package kr.ac.pusan.pickle.relay;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-relay mapping-generation counter. Every mapping write (create, delete,
 * suspend/unsuspend, guard edit, teardown) bumps the OWNING relay's counter in
 * the same transaction, so the agent's change detection is one integer
 * comparison against its own relay row — deliberately a per-relay column,
 * never a global sequence (a shared sequence would make every relay's agent
 * re-fetch on any other relay's change and could pair a foreign generation
 * with this relay's snapshot).
 *
 * <p>Side effect relied on for correctness: the UPDATE takes a row lock on the
 * relay until commit, serializing all mapping writes of one relay. Callers
 * bump BEFORE touching {@code port_mappings}, which is what makes the
 * cross-proto exclusive port allocation race-free.</p>
 */
@Component
public class RelayGenerations {

    private final JdbcTemplate jdbcTemplate;

    public RelayGenerations(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Increments and returns the relay's mapping generation (row-locking). */
    public long bump(long relayId) {
        Long next = jdbcTemplate.queryForObject("""
                update relays
                   set mapping_generation = mapping_generation + 1, updated_at = now()
                 where id = ?
                returning mapping_generation
                """, Long.class, relayId);
        if (next == null) {
            throw new IllegalStateException("unknown relay: " + relayId);
        }
        return next;
    }
}
