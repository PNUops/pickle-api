package kr.ac.pusan.pickle.audit;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

/**
 * Public identifiers for audit {@code detail}, resolved from the internal key a
 * call site happens to be holding.
 *
 * <p>{@code detail} is free-form jsonb and reaches ordinary users through
 * {@code /me/activity}, so an internal row number in it leaks exactly what a
 * public id exists to stop leaking. Most call sites have the entity in hand and
 * call {@code getPublicId()} directly; this is for the ones that only ever had a
 * {@code long} — a mapping id just allocated, a VM id carried on a session, a
 * foreign key read off another row.</p>
 *
 * <p>One indexed primary-key lookup per call, on a path that already writes a
 * row. Not a general-purpose translator: an id belongs here only because it is
 * about to be written into an audit payload.</p>
 */
@Component
public class AuditIds {

    private final JdbcTemplate jdbcTemplate;

    public AuditIds(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public @Nullable UUID user(@Nullable Long id) {
        return one("select public_id from users where id = ?", id);
    }

    public @Nullable UUID org(@Nullable Long id) {
        return one("select public_id from orgs where id = ?", id);
    }

    public @Nullable UUID workspace(@Nullable Long id) {
        return one("select public_id from workspaces where id = ?", id);
    }

    public @Nullable UUID vm(@Nullable Long id) {
        return one("select public_id from vms where id = ?", id);
    }

    public @Nullable UUID node(@Nullable Long id) {
        return one("select public_id from nodes where id = ?", id);
    }

    public @Nullable UUID osImage(@Nullable Long id) {
        return one("select public_id from os_images where id = ?", id);
    }

    public @Nullable UUID relay(@Nullable Long id) {
        return one("select public_id from relays where id = ?", id);
    }

    public @Nullable UUID portMapping(@Nullable Long id) {
        return one("select public_id from port_mappings where id = ?", id);
    }

    public @Nullable UUID sshKey(@Nullable Long id) {
        return one("select public_id from vm_ssh_keys where id = ?", id);
    }

    /** Same order as the input, so a list stays aligned with its siblings. */
    public List<UUID> users(Collection<Long> ids) {
        return many("select id, public_id from users where id in ", ids);
    }

    /** Same order as the input, so a list stays aligned with its siblings. */
    public List<UUID> sshKeys(Collection<Long> ids) {
        return many("select id, public_id from vm_ssh_keys where id in ", ids);
    }

    // ── internals ──────────────────────────────────────────────────────────

    /**
     * Null in, null out: several of these come from nullable foreign keys, and
     * an audit write must not fail because the thing it describes is gone.
     */
    private @Nullable UUID one(String sql, @Nullable Long id) {
        if (id == null) {
            return null;
        }
        return jdbcTemplate.query(sql, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, id);
    }

    /**
     * One statement for the whole list. {@code sqlPrefix} is a constant from a
     * method above and the only text appended to it is the placeholder list, so
     * nothing caller-supplied ever reaches the SQL.
     */
    private List<UUID> many(String sqlPrefix, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = "(" + String.join(", ", Collections.nCopies(ids.size(), "?")) + ")";
        Map<Long, UUID> found = new HashMap<>();
        jdbcTemplate.query(sqlPrefix + placeholders, (RowCallbackHandler) rs ->
                found.put(rs.getLong(1), rs.getObject(2, UUID.class)),
                ids.toArray());
        return ids.stream().map(found::get).filter(Objects::nonNull).toList();
    }
}
