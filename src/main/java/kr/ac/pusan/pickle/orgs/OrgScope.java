package kr.ac.pusan.pickle.orgs;

import java.util.Collection;
import java.util.List;

/**
 * The organisations one admin query is allowed to touch.
 *
 * <p>Replaces the single {@code Long} the scope resolvers used to return, now
 * that an account can administer several organisations (V90). Three states,
 * and the third is the one the old {@code Long} could not express without a
 * sentinel: unrestricted (no clause at all), a set, and the empty set, which
 * matches nothing and is how a filter naming an organisation that does not
 * exist answers for a sys-tier caller.
 */
public final class OrgScope {

    private static final OrgScope UNRESTRICTED = new OrgScope(null);
    private static final OrgScope NOTHING = new OrgScope(List.of());

    /** Null means unrestricted; an empty list means nothing matches. */
    private final List<Long> orgIds;

    private OrgScope(List<Long> orgIds) {
        this.orgIds = orgIds;
    }

    /** Every organisation: the query gets no org clause. */
    public static OrgScope unrestricted() {
        return UNRESTRICTED;
    }

    /** No organisation: the query is filtered down to nothing. */
    public static OrgScope nothing() {
        return NOTHING;
    }

    public static OrgScope of(Long orgId) {
        return orgId == null ? UNRESTRICTED : new OrgScope(List.of(orgId));
    }

    public static OrgScope of(Collection<Long> orgIds) {
        return orgIds == null ? UNRESTRICTED : new OrgScope(List.copyOf(orgIds));
    }

    public boolean isUnrestricted() {
        return orgIds == null;
    }

    /** The ids to bind, in the order {@link #inList} expects them. */
    public List<Long> orgIds() {
        return orgIds == null ? List.of() : orgIds;
    }

    /**
     * The guard the aggregate queries use, in the shape they already had:
     * <b>two</b> bound parameters whatever the size of the scope, both of them
     * {@link #arrayParam()}. Unrestricted binds null twice and the guard is
     * true; the empty set binds an empty array and the guard is false for every
     * row.
     */
    public String guard(String column) {
        return "(?::bigint[] is null or " + column + " = any(?::bigint[]))";
    }

    /**
     * The scope as a Postgres array literal, or null when unrestricted. Bound
     * twice per {@link #guard}.
     */
    public String arrayParam() {
        if (orgIds == null) {
            return null;
        }
        StringBuilder literal = new StringBuilder("{");
        for (int i = 0; i < orgIds.size(); i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(orgIds.get(i).longValue());
        }
        return literal.append('}').toString();
    }

    /**
     * A restricting predicate for {@code column}, or an empty string when
     * unrestricted so the caller appends nothing. The empty set renders
     * {@code in (null)}, which is unknown and so excludes every row.
     */
    public String inList(String column) {
        if (orgIds == null) {
            return "";
        }
        if (orgIds.isEmpty()) {
            return column + " in (null)";
        }
        return column + " in (" + "?, ".repeat(orgIds.size() - 1) + "?)";
    }
}
