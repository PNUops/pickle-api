package kr.ac.pusan.pickle.llm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The document-generation counter the gateway polls against: a single row in
 * {@code llm_gateway_state}, bumped with a row-locking upsert — deliberately
 * never a sequence. The upsert takes a row lock until commit, which is what
 * makes commit order and generation order agree; with a sequence, a
 * transaction holding the lower number can commit second, and a poll at the
 * higher generation reads a table that does not yet contain that change —
 * which then never reaches the gateway at all, because nothing bumps again.
 *
 * <p><b>Every write the document is built from (key issue, revoke, suspend,
 * limit edit, record-bodies toggle, service kill switch — and model writes
 * once the catalogue exists) must call {@link #bump()} BEFORE touching the
 * rows, in the same transaction.</b> Same discipline as the relay mapping
 * counter.</p>
 *
 * <p>The row is not seeded by any migration (migrations carry schema, not
 * rows): both operations here are upserts, so the row comes into existence on
 * the first write — the sync handler's contact stamp or the first key
 * write.</p>
 */
@Component
public class LlmGatewayGenerations {

    private final JdbcTemplate jdbcTemplate;

    public LlmGatewayGenerations(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Increments and returns the document generation (row-locking upsert). */
    public long bump() {
        return jdbcTemplate.queryForObject("""
                insert into llm_gateway_state (id) values (true)
                on conflict (id) do update
                   set generation = llm_gateway_state.generation + 1, updated_at = now()
                returning generation
                """, Long.class);
    }

    /**
     * Raises the counter strictly above a floor the gateway already served —
     * the restored-backup path: the api's counter went backwards while the
     * gateway's persisted high-water mark did not, and every document this
     * side could otherwise produce sits below the gateway's floor forever.
     * {@code greatest} keeps a concurrent {@link #bump()} from being undone.
     */
    public long raiseAbove(long floor) {
        return jdbcTemplate.queryForObject("""
                insert into llm_gateway_state (id, generation) values (true, ?)
                on conflict (id) do update
                   set generation = greatest(llm_gateway_state.generation + 1, excluded.generation),
                       updated_at = now()
                returning generation
                """, Long.class, floor + 1);
    }
}
