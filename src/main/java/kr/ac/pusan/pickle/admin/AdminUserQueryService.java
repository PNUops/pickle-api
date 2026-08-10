package kr.ac.pusan.pickle.admin;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.admin.dto.UserAdminDetailResponse;
import kr.ac.pusan.pickle.admin.dto.UserAdminViewResponse;
import kr.ac.pusan.pickle.admin.dto.UserStatusChangeResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.mfa.UserMfaRepository;
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
 * masked as 404. {@code mfaEnabled} reflects live {@code user_mfa} enrollment
 * (batch-loaded for the list, single lookup for the detail).
 */
@Service
public class AdminUserQueryService {
    /**
     * The scope an id no org has resolves to: a filter value no row carries, so
     * the page comes back empty exactly as a non-matching number made it.
     */
    private static final Long NO_SUCH_ORG = -1L;


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
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final VmRepository vmRepository;
    private final UserStatusChangeRepository userStatusChangeRepository;
    private final UserMfaRepository userMfaRepository;

    public AdminUserQueryService(JdbcTemplate jdbcTemplate, UserRepository userRepository,
            WorkspaceMemberRepository workspaceMemberRepository, VmRepository vmRepository,
            UserStatusChangeRepository userStatusChangeRepository,
            UserMfaRepository userMfaRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.vmRepository = vmRepository;
        this.userStatusChangeRepository = userStatusChangeRepository;
        this.userMfaRepository = userMfaRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserAdminViewResponse> listUsers(AuthenticatedUser actor, String q,
            UserStatus status, UserRole role, UUID orgId, String sort, int page, int size) {
        Long scopedOrgId = scopeOrgId(actor, orgId);
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> params = new ArrayList<>();
        if (scopedOrgId != null) {
            // Derived-org scoping in SQL: the org's ORG_ADMINs plus ACTIVE
            // members of a workspace linked to the org (derived org membership rule).
            where.append(" and (u.org_id = ? or (u.status = 'ACTIVE' and ")
                    .append(OrgMembershipSql.memberOfOrgLinkedWorkspace("u.id"))
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
        // u.public_id and o.public_id are selected beside the internal keys: the
        // response carries the public pair, and u.id is still what the 2FA
        // set-membership query below joins on.
        List<Row> rows = jdbcTemplate.query("""
                select u.id, u.public_id, u.email, u.name, u.role, o.public_id as org_public_id,
                       u.status, u.created_at
                """ + base + " left join orgs o on o.id = u.org_id" + where
                + " order by " + resolveOrder(sort) + " limit ? offset ?",
                (rs, rowNum) -> mapRow(rs), pageParams.toArray());
        // Real 2FA state: one set-membership query over the page's user ids
        // (the console mfa-reset button keys off this).
        Set<Long> enrolled = rows.isEmpty() ? Set.of()
                : Set.copyOf(userMfaRepository.findEnrolledUserIds(
                        rows.stream().map(Row::id).toList()));
        List<UserAdminViewResponse> content = rows.stream()
                .map(v -> new UserAdminViewResponse(v.publicId(), v.email(), v.name(), v.role(),
                        v.orgId(), v.status(), enrolled.contains(v.id()), v.createdAt()))
                .toList();
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }

    @Transactional(readOnly = true)
    public UserAdminDetailResponse getUser(AuthenticatedUser actor, UUID userId) {
        Long scopedOrgId = orgIdIfOrgAdmin(actor);
        User user = userRepository.findByPublicId(userId).filter(u -> inScope(u.getId(), scopedOrgId))
                .orElseThrow(AdminUserQueryService::userNotFound);

        List<WorkspaceMember> liveMemberships = workspaceMemberRepository.findWithWorkspaceByUserId(user.getId()).stream()
                .filter(member -> member.getWorkspace().getDeletedAt() == null)
                .toList();
        List<UserProfileResponse.Membership> memberships = liveMemberships.stream()
                .map(UserProfileResponse.Membership::from)
                .toList();
        List<Long> workspaceIds = liveMemberships.stream().map(m -> m.getWorkspace().getId()).toList();
        int activeVmCount = workspaceIds.isEmpty() ? 0
                : (int) vmRepository.countActiveByWorkspaceIdIn(workspaceIds, VmStatus.DELETED);

        List<UserStatusChangeResponse> statusChanges =
                mapStatusChanges(userStatusChangeRepository.findByUserIdOrderByChangedAtDescIdDesc(user.getId()));

        return new UserAdminDetailResponse(user.getPublicId(), user.getEmail(), user.getName(),
                user.getRole(), orgPublicId(user.getOrgId()), user.getStatus(),
                userMfaRepository.isEnrolled(user.getId()),
                user.getCreatedAt(),
                user.getWithdrawnAt(), user.getDisabledAt(), user.getDisabledReason(),
                memberships, activeVmCount, statusChanges);
    }

    /** Resolves each transition's actor email in one batch. */
    private List<UserStatusChangeResponse> mapStatusChanges(List<UserStatusChange> changes) {
        List<Long> actorIds = changes.stream().map(UserStatusChange::getActorId)
                .filter(id -> id != null).distinct().toList();
        List<User> actors = userRepository.findAllById(actorIds);
        Map<Long, String> emails = actors.stream()
                .collect(Collectors.toMap(User::getId, User::getEmail));
        Map<Long, UUID> publicIds = actors.stream()
                .collect(Collectors.toMap(User::getId, User::getPublicId));
        return changes.stream()
                .map(change -> new UserStatusChangeResponse(change.getFromStatus(), change.getToStatus(),
                        publicIds.get(change.getActorId()), emails.get(change.getActorId()),
                        change.getReason(), change.getChangedAt()))
                .toList();
    }

    private boolean inScope(long userId, Long scopedOrgId) {
        if (scopedOrgId == null) {
            return true;
        }
        Boolean visible = jdbcTemplate.queryForObject(
                "select exists (select 1 from users u where u.id = ? and (u.org_id = ?"
                        + " or (u.status = 'ACTIVE' and " + OrgMembershipSql.memberOfOrgLinkedWorkspace("u.id")
                        + ")))",
                Boolean.class, userId, scopedOrgId, scopedOrgId, scopedOrgId);
        return Boolean.TRUE.equals(visible);
    }

    /** One list row: the internal key the 2FA join needs, plus the public view. */
    private record Row(long id, UUID publicId, String email, String name, UserRole role,
            UUID orgId, UserStatus status, java.time.Instant createdAt) {
    }

    private static Row mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getLong("id"), rs.getObject("public_id", UUID.class),
                rs.getString("email"), rs.getString("name"),
                UserRole.valueOf(rs.getString("role")), rs.getObject("org_public_id", UUID.class),
                UserStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private UUID orgPublicId(Long orgId) {
        return orgId == null ? null : jdbcTemplate.query(
                "select public_id from orgs where id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, orgId);
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
    private Long scopeOrgId(AuthenticatedUser actor, UUID orgId) {
        Long requested = orgId == null ? null : jdbcTemplate.query(
                "select id from orgs where public_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, orgId);
        if (!actor.role().isOrgTier()) {
            // An id no org has filters to nothing, as a non-matching number did.
            if (orgId != null && requested == null) {
                return NO_SUCH_ORG;
            }
            return requested;
        }
        if (actor.orgId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "관리 기관이 지정되지 않은 계정입니다.");
        }
        if (orgId != null && !actor.orgId().equals(requested)) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                    "리소스를 찾을 수 없습니다", "해당 기관을 찾을 수 없습니다.");
        }
        return actor.orgId();
    }
}
