package kr.ac.pusan.pickle.audit;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.audit.dto.ActivityEntryResponse;
import kr.ac.pusan.pickle.audit.dto.AuditLogViewResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.orgs.OrgMembershipSql;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read side of {@code audit_logs} (contract {@code listMyActivity} /
 * {@code listAuditLogs}). JdbcTemplate-only by design — the table is
 * append-only with no JPA entity, so no accidental mutation path exists.
 *
 * <p>Scoping is enforced <b>in SQL</b>: {@code /me/activity} is hard-bound to
 * {@code actor_id = principal}, and {@code /admin/audit} pins ORG_ADMIN to
 * rows whose actor belongs to their org by the canonical <b>derived
 * membership</b> rule ({@link OrgMembershipSql}: the actor is one of the
 * org's ORG_ADMINs, or an ACTIVE member of a workspace with requests /
 * non-DELETED VMs in the org). System rows — null actor — are therefore
 * SYS_ADMIN-only. Date bounds are KST calendar days.</p>
 */
@Service
public class AuditQueryService {
    /**
     * The scope an id no org has resolves to: a filter value no row carries, so
     * the page comes back empty exactly as a non-matching number made it.
     */

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * Representative derived-org name for the audit row's actor (v0.9.0 display
     * field): the actor's managed org (ORG_ADMIN {@code the actor's administered orgs}) if any,
     * else the smallest org id derived from the actor's workspace resources
     * (requests / non-DELETED VMs — the canonical rule, {@link OrgMembershipSql}).
     * Null for system rows and actors with no derived org. Correlated on
     * {@code u}/{@code a} from the outer query; binds no positional parameters.
     */
    private static final String ACTOR_ORG_NAME = """
            (select o.name from orgs o where o.id = coalesce(
                 (select min(uor.org_id) from user_org_roles uor where uor.user_id = a.actor_id), (
                 select min(dro.org_id) from (
                     select lr.org_id from requests lr
                       join workspace_members gm on gm.workspace_id = lr.workspace_id
                      where gm.user_id = a.actor_id
                     union
                     select lv.org_id from vms lv
                       join workspace_members gm2 on gm2.workspace_id = lv.workspace_id
                      where gm2.user_id = a.actor_id and lv.status <> 'DELETED'
                 ) dro)))""";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuditQueryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<ActivityEntryResponse> myActivity(long actorId, String action,
            LocalDate from, LocalDate to, int page, int size) {
        StringBuilder where = new StringBuilder(" where a.actor_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(actorId);
        appendFilters(where, params, "a", action, from, to);

        long total = queryCount("select count(*) from audit_logs a" + where, params);
        params.add(size);
        params.add((long) page * size);
        List<ActivityEntryResponse> content = jdbcTemplate.query("""
                select a.public_id, a.action, a.target_type, a.target_id, a.detail, a.ip,
                       a.created_at
                  from audit_logs a"""
                + where + " order by a.created_at desc, a.id desc limit ? offset ?",
                (rs, rowNum) -> new ActivityEntryResponse(rs.getObject("public_id", UUID.class),
                        rs.getString("action"), rs.getString("target_type"),
                        rs.getString("target_id"),
                        detailOf(rs.getString("detail")), rs.getString("ip"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                params.toArray());
        return pageOf(content, page, size, total);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogViewResponse> adminAudit(AuthenticatedUser actor,
            String actorEmail, String action, String targetType, String targetId,
            LocalDate from, LocalDate to, UUID orgId, int page, int size) {
        OrgScope scope = scopeOrgId(actor, orgId);
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> params = new ArrayList<>();
        if (!scope.isUnrestricted()) {
            // actor-org scoping in SQL (never post-filtered): the orgs' own
            // administrators plus derived members; system rows (null actor) and
            // out-of-scope actors drop out
            where.append(" and (exists (select 1 from user_org_roles uor")
                    .append(" where uor.user_id = u.id and ")
                    .append(scope.inList("uor.org_id"))
                    .append(") or (u.status = 'ACTIVE' and ")
                    .append(OrgMembershipSql.memberOfOrgLinkedWorkspace("a.actor_id", scope))
                    .append("))");
            params.addAll(scope.orgIds());
            params.addAll(scope.orgIds());
            params.addAll(scope.orgIds());
        }
        if (actorEmail != null && !actorEmail.isBlank()) {
            where.append(" and u.email = ?"); // citext — case-insensitive
            params.add(actorEmail.strip());
        }
        if (targetType != null && !targetType.isBlank()) {
            where.append(" and a.target_type = ?");
            params.add(targetType.strip());
        }
        if (targetId != null && !targetId.isBlank()) {
            where.append(" and a.target_id::text = ?");
            params.add(targetId.strip());
        }
        appendFilters(where, params, "a", action, from, to);

        String base = " from audit_logs a left join users u on u.id = a.actor_id";
        long total = queryCount("select count(*)" + base + where, params);
        params.add(size);
        params.add((long) page * size);
        // u.public_id, not a.actor_id: the actor is reported by the identifier
        // the API names accounts with. actor_id itself stays the internal key
        // the join runs on. Same for a.public_id — a.id orders the page and
        // never leaves the server.
        List<AuditLogViewResponse> content = jdbcTemplate.query("select a.public_id, "
                + "u.public_id as actor_public_id, "
                + "a.actor_role, a.action, a.target_type, a.target_id, a.detail, a.ip, "
                + "a.created_at, u.email as actor_email, u.name as actor_name, "
                + ACTOR_ORG_NAME + " as org_name"
                + base + where + " order by a.created_at desc, a.id desc limit ? offset ?",
                (rs, rowNum) -> new AuditLogViewResponse(rs.getObject("public_id", UUID.class),
                        rs.getObject("actor_public_id", UUID.class), rs.getString("actor_email"),
                        rs.getString("actor_name"), rs.getString("actor_role"),
                        rs.getString("action"), rs.getString("target_type"),
                        rs.getString("target_id"),
                        detailOf(rs.getString("detail")), rs.getString("ip"),
                        rs.getString("org_name"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                params.toArray());
        return pageOf(content, page, size, total);
    }

    // ── shared pieces ──────────────────────────────────────────────────────

    private void appendFilters(StringBuilder where, List<Object> params, String alias,
            String action, LocalDate from, LocalDate to) {
        if (action != null && !action.isBlank()) {
            where.append(" and ").append(alias).append(".action = ?");
            params.add(action.strip());
        }
        if (from != null) {
            where.append(" and ").append(alias).append(".created_at >= ?");
            params.add(Timestamp.from(from.atStartOfDay(KST).toInstant()));
        }
        if (to != null) {
            // inclusive KST day: everything before the next day's 00:00
            where.append(" and ").append(alias).append(".created_at < ?");
            params.add(Timestamp.from(to.plusDays(1).atStartOfDay(KST).toInstant()));
        }
    }

    private long queryCount(String sql, List<Object> params) {
        Long total = jdbcTemplate.queryForObject(sql, Long.class, params.toArray());
        return total != null ? total : 0;
    }

    private static <T> PageResponse<T> pageOf(List<T> content, int page, int size, long total) {
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        return new PageResponse<>(content, page, size, total, totalPages);
    }

    private JsonNode detailOf(String detailJson) {
        return detailJson != null ? objectMapper.readTree(detailJson) : null;
    }

    /** Org tier pinned to their org; another org's id answers 404. */
    private OrgScope scopeOrgId(AuthenticatedUser actor, UUID orgId) {
        Long requested = orgId == null ? null : jdbcTemplate.query(
                "select id from orgs where public_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, orgId);
        if (!actor.role().isOrgTier()) {
            // An id no org has filters to nothing, as a non-matching number did.
            if (orgId != null && requested == null) {
                return OrgScope.nothing();
            }
            return OrgScope.of(requested);
        }
        if (actor.managedOrgIds().isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "관리 기관이 지정되지 않은 계정입니다.");
        }
        if (orgId != null && !actor.manages(requested)) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                    "리소스를 찾을 수 없습니다", "해당 기관을 찾을 수 없습니다.");
        }
        return orgId != null ? OrgScope.of(requested) : OrgScope.of(actor.managedOrgIds());
    }
}
