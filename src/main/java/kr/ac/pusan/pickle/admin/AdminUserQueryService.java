package kr.ac.pusan.pickle.admin;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.admin.dto.UserAdminDetailResponse;
import kr.ac.pusan.pickle.admin.dto.UserAdminViewResponse;
import kr.ac.pusan.pickle.admin.dto.UserStatusChangeResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.orgs.OrgMembershipSql;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.user.UserStatusChange;
import kr.ac.pusan.pickle.user.UserStatusChangeRepository;
import kr.ac.pusan.pickle.user.dto.UserProfileResponse;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the admin user surface ({@code GET /admin/users} and
 * {@code /{userId}}). Scoping is enforced <b>in SQL</b>: SYS_ADMIN sees every
 * user; ORG_ADMIN is pinned to their org by the canonical <b>derived
 * membership</b> rule ({@link OrgMembershipSql}) — an out-of-scope user is
 * masked as 404. {@code mfaEnabled} is hardcoded false until W2-A adds 2FA.
 */
@Service
public class AdminUserQueryService {

    /** Whitelisted {@code sort} → SQL order-by. Default is latest signup ({@code -id}). */
    private static final Map<String, String> SORTS = Map.of(
            "name", "u.name asc",
            "-name", "u.name desc",
            "email", "u.email asc",
            "-email", "u.email desc",
            "createdAt", "u.created_at asc",
            "-createdAt", "u.created_at desc");

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final VmRepository vmRepository;
    private final UserStatusChangeRepository userStatusChangeRepository;

    public AdminUserQueryService(JdbcTemplate jdbcTemplate, UserRepository userRepository,
            GroupMemberRepository groupMemberRepository, VmRepository vmRepository,
            UserStatusChangeRepository userStatusChangeRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.vmRepository = vmRepository;
        this.userStatusChangeRepository = userStatusChangeRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserAdminViewResponse> listUsers(AuthenticatedUser actor, String q,
            UserStatus status, UserRole role, Long orgId, String sort, int page, int size) {
        Long scopedOrgId = scopeOrgId(actor, orgId);
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> params = new ArrayList<>();
        if (scopedOrgId != null) {
            // Derived-org scoping in SQL: the org's ORG_ADMINs plus ACTIVE
            // members of a group linked to the org (product-spec §14 rule).
            where.append(" and (u.org_id = ? or (u.status = 'ACTIVE' and ")
                    .append(OrgMembershipSql.memberOfOrgLinkedGroup("u.id"))
                    .append("))");
            params.add(scopedOrgId);
            params.add(scopedOrgId);
            params.add(scopedOrgId);
        }
        if (q != null && !q.isBlank()) {
            String pattern = "%" + escapeLike(q.strip()) + "%";
            where.append(" and (u.email ilike ? escape '\\' or u.name ilike ? escape '\\')");
            params.add(pattern);
            params.add(pattern);
        }
        if (status != null) {
            where.append(" and u.status::text = ?");
            params.add(status.name());
        }
        if (role != null) {
            where.append(" and u.role::text = ?");
            params.add(role.name());
        }

        String base = " from users u";
        Long total = jdbcTemplate.queryForObject("select count(*)" + base + where, Long.class,
                params.toArray());
        long totalElements = total != null ? total : 0;

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((long) page * size);
        List<UserAdminViewResponse> content = jdbcTemplate.query("""
                select u.id, u.email, u.name, u.role, u.org_id, u.status, u.created_at
                """ + base + where + " order by " + resolveOrder(sort) + " limit ? offset ?",
                (rs, rowNum) -> mapView(rs), pageParams.toArray());
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }

    @Transactional(readOnly = true)
    public UserAdminDetailResponse getUser(AuthenticatedUser actor, long userId) {
        Long scopedOrgId = scopeOrgId(actor, orgIdIfOrgAdmin(actor));
        User user = userRepository.findById(userId).filter(u -> inScope(u.getId(), scopedOrgId))
                .orElseThrow(AdminUserQueryService::userNotFound);

        List<GroupMember> liveMemberships = groupMemberRepository.findWithGroupByUserId(user.getId()).stream()
                .filter(member -> member.getGroup().getDeletedAt() == null)
                .toList();
        List<UserProfileResponse.Membership> memberships = liveMemberships.stream()
                .map(UserProfileResponse.Membership::from)
                .toList();
        List<Long> groupIds = liveMemberships.stream().map(m -> m.getGroup().getId()).toList();
        int activeVmCount = groupIds.isEmpty() ? 0
                : (int) vmRepository.countActiveByGroupIdIn(groupIds, VmStatus.DELETED);

        List<UserStatusChangeResponse> statusChanges =
                mapStatusChanges(userStatusChangeRepository.findByUserIdOrderByChangedAtDescIdDesc(user.getId()));

        return new UserAdminDetailResponse(user.getId(), user.getEmail(), user.getName(), user.getRole(),
                user.getOrgId(), user.getStatus(), false, user.getCreatedAt(),
                user.getWithdrawnAt(), user.getDisabledAt(), user.getDisabledReason(),
                memberships, activeVmCount, statusChanges);
    }

    /** Resolves each transition's actor email in one batch. */
    private List<UserStatusChangeResponse> mapStatusChanges(List<UserStatusChange> changes) {
        List<Long> actorIds = changes.stream().map(UserStatusChange::getActorId)
                .filter(id -> id != null).distinct().toList();
        Map<Long, String> emails = userRepository.findAllById(actorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getEmail));
        return changes.stream()
                .map(change -> new UserStatusChangeResponse(change.getFromStatus(), change.getToStatus(),
                        change.getActorId(), emails.get(change.getActorId()), change.getReason(),
                        change.getChangedAt()))
                .toList();
    }

    private boolean inScope(long userId, Long scopedOrgId) {
        if (scopedOrgId == null) {
            return true;
        }
        Boolean visible = jdbcTemplate.queryForObject(
                "select exists (select 1 from users u where u.id = ? and (u.org_id = ?"
                        + " or (u.status = 'ACTIVE' and " + OrgMembershipSql.memberOfOrgLinkedGroup("u.id")
                        + ")))",
                Boolean.class, userId, scopedOrgId, scopedOrgId, scopedOrgId);
        return Boolean.TRUE.equals(visible);
    }

    private static UserAdminViewResponse mapView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserAdminViewResponse(rs.getLong("id"), rs.getString("email"), rs.getString("name"),
                UserRole.valueOf(rs.getString("role")), rs.getObject("org_id", Long.class),
                UserStatus.valueOf(rs.getString("status")), false,
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static String resolveOrder(String sort) {
        if (sort == null || sort.isBlank()) {
            return "u.id desc";
        }
        String order = SORTS.get(sort);
        if (order == null) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("sort",
                    "정렬 기준은 " + new java.util.TreeSet<>(SORTS.keySet()) + " 중 하나여야 합니다.")));
        }
        return order + ", u.id desc";
    }

    private static Long orgIdIfOrgAdmin(AuthenticatedUser actor) {
        return actor.role().isOrgTier() ? actor.orgId() : null;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static ApiException userNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "사용자를 찾을 수 없습니다", "해당 ID의 사용자가 존재하지 않습니다.");
    }

    /** Org tier pinned to their org; another org's id answers 404 (mask). */
    private static Long scopeOrgId(AuthenticatedUser actor, Long orgId) {
        if (!actor.role().isOrgTier()) {
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
