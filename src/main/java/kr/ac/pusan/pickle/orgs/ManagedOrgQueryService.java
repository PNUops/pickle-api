package kr.ac.pusan.pickle.orgs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.orgs.dto.ManagedOrgResponse;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the organisations an account holds a role in, named for a response.
 * Every role, the read-only one included — the response says which.
 *
 * <p>Batched because the admin user list renders a page of accounts at once and
 * a per-row lookup would be one query per user.
 */
@Service
public class ManagedOrgQueryService {

    private final JdbcTemplate jdbcTemplate;

    public ManagedOrgQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<ManagedOrgResponse> of(Long userId) {
        return byUser(List.of(userId)).getOrDefault(userId, List.of());
    }

    /** Ordered by org name so the list reads the same way every time. */
    @Transactional(readOnly = true)
    public Map<Long, List<ManagedOrgResponse>> byUser(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = List.copyOf(userIds);
        String placeholders = "?, ".repeat(ids.size() - 1) + "?";
        Map<Long, List<ManagedOrgResponse>> byUser = new LinkedHashMap<>();
        jdbcTemplate.query(
                "select uor.user_id, o.public_id, o.name, uor.role::text"
                        + " from user_org_roles uor join orgs o on o.id = uor.org_id"
                        + " where uor.user_id in (" + placeholders + ")"
                        + " order by o.name, o.id",
                rs -> {
                    byUser.computeIfAbsent(rs.getLong(1), key -> new ArrayList<>())
                            .add(new ManagedOrgResponse(rs.getObject(2, UUID.class),
                                    rs.getString(3), UserRole.valueOf(rs.getString(4))));
                },
                ids.toArray());
        return byUser;
    }
}
