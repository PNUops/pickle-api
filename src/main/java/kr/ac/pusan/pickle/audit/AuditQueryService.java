package kr.ac.pusan.pickle.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import kr.ac.pusan.pickle.audit.dto.ActivityEntryResponse;
import kr.ac.pusan.pickle.audit.dto.AuditLogViewResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.orgs.OrgMembershipSql;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
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
 * org's ORG_ADMINs, or an ACTIVE member of a group with vm_requests /
 * non-DELETED VMs in the org). System rows — null actor — are therefore
 * SYS_ADMIN-only. Date bounds are KST calendar days.</p>
 */
@Service
public class AuditQueryService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

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
                select a.id, a.action, a.target_type, a.target_id, a.detail, a.ip, a.created_at
                  from audit_logs a"""
                + where + " order by a.created_at desc, a.id desc limit ? offset ?",
                (rs, rowNum) -> new ActivityEntryResponse(rs.getLong("id"),
                        rs.getString("action"), rs.getString("target_type"),
                        targetIdOf(rs.getObject("target_id", Long.class)),
                        detailOf(rs.getString("detail")), rs.getString("ip"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                params.toArray());
        return pageOf(content, page, size, total);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogViewResponse> adminAudit(AuthenticatedUser actor,
            String actorEmail, String action, String targetType, String targetId,
            LocalDate from, LocalDate to, Long orgId, int page, int size) {
        Long scopedOrgId = scopeOrgId(actor, orgId);
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> params = new ArrayList<>();
        if (scopedOrgId != null) {
            // actor-org scoping in SQL (never post-filtered): the org's
            // ORG_ADMINs plus derived members; system rows (null actor) and
            // out-of-org actors drop out
            where.append(" and (u.org_id = ? or (u.status = 'ACTIVE' and ")
                    .append(OrgMembershipSql.memberOfOrgLinkedGroup("a.actor_id"))
                    .append("))");
            params.add(scopedOrgId);
            params.add(scopedOrgId);
            params.add(scopedOrgId);
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
        List<AuditLogViewResponse> content = jdbcTemplate.query("""
                select a.id, a.actor_id, a.actor_role, a.action, a.target_type, a.target_id,
                       a.detail, a.ip, a.created_at, u.email as actor_email, u.name as actor_name
                """ + base + where + " order by a.created_at desc, a.id desc limit ? offset ?",
                (rs, rowNum) -> new AuditLogViewResponse(rs.getLong("id"),
                        rs.getObject("actor_id", Long.class), rs.getString("actor_email"),
                        rs.getString("actor_name"), rs.getString("actor_role"),
                        rs.getString("action"), rs.getString("target_type"),
                        targetIdOf(rs.getObject("target_id", Long.class)),
                        detailOf(rs.getString("detail")), rs.getString("ip"),
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

    private static String targetIdOf(Long targetId) {
        return targetId != null ? String.valueOf(targetId) : null;
    }

    private JsonNode detailOf(String detailJson) {
        return detailJson != null ? objectMapper.readTree(detailJson) : null;
    }

    /** ORG_ADMIN pinned to their org; another org's id answers 404. */
    private static Long scopeOrgId(AuthenticatedUser actor, Long orgId) {
        if (actor.role() != UserRole.ORG_ADMIN) {
            return orgId;
        }
        if (actor.orgId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "관리 기관이 지정되지 않은 계정입니다.");
        }
        if (orgId != null && !orgId.equals(actor.orgId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                    "리소스를 찾을 수 없습니다", "해당 기관을 찾을 수 없습니다.");
        }
        return actor.orgId();
    }
}
