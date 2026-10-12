package kr.ac.pusan.pickle.admin;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.admin.dto.AdminMembershipResponse;
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
import kr.ac.pusan.pickle.orgs.ManagedOrgQueryService;
import kr.ac.pusan.pickle.orgs.dto.ManagedOrgResponse;
import kr.ac.pusan.pickle.orgs.OrgMembershipSql;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.profile.ProfileOptionsService;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.user.UserStatusChange;
import kr.ac.pusan.pickle.user.UserStatusChangeRepository;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Global support directory and account profile reads. Institution readers are not
 * answered for system-tier accounts. The optional institution narrows derived
 * membership; an unknown filter returns an empty page. Detail memberships omit
 * deleted workspaces, and live MFA state is loaded independently of account flags.
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

    /** The roles {@link UserRole#isSysTier()} answers for, as SQL parameters. */
    private static final List<String> SYS_TIER_ROLES = java.util.Arrays.stream(UserRole.values())
            .filter(UserRole::isSysTier)
            .map(Enum::name)
            .toList();

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final VmRepository vmRepository;
    private final UserStatusChangeRepository userStatusChangeRepository;
    private final UserMfaRepository userMfaRepository;
    private final ManagedOrgQueryService managedOrgQueryService;
    private final ProfileOptionsService profileOptionsService;

    public AdminUserQueryService(JdbcTemplate jdbcTemplate, UserRepository userRepository,
            WorkspaceMemberRepository workspaceMemberRepository, VmRepository vmRepository,
            UserStatusChangeRepository userStatusChangeRepository,
            UserMfaRepository userMfaRepository,
            ManagedOrgQueryService managedOrgQueryService,
            ProfileOptionsService profileOptionsService) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.vmRepository = vmRepository;
        this.userStatusChangeRepository = userStatusChangeRepository;
        this.userMfaRepository = userMfaRepository;
        this.managedOrgQueryService = managedOrgQueryService;
        this.profileOptionsService = profileOptionsService;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserAdminViewResponse> listUsers(AuthenticatedUser actor, String q,
            UserStatus status, UserRole role, UUID orgId, String sort, int page, int size) {
        StudentSupportPolicy.requireRead(actor);
        OrgScope scope = scopeOrgId(actor, orgId);
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> params = new ArrayList<>();
        if (actor.role().isOrgTier()) {
            // System-tier accounts are withheld from the org tier (operator,
            // 2026-09-16): it can act on none of them. Filtered in SQL so the
            // count and the page boundaries agree with the rows, and taken from
            // the enum so a new system role cannot slip past the list while the
            // detail (which asks isSysTier) still withholds it.
            where.append(" and u.role::text not in (")
                    .append(SYS_TIER_ROLES.stream().map(r -> "?").collect(Collectors.joining(", ")))
                    .append(")");
            params.addAll(SYS_TIER_ROLES);
        }
        if (!scope.isUnrestricted()) {
            // Derived-org scoping in SQL: the org's own administrators plus
            // ACTIVE members of a workspace linked to it (derived membership).
            where.append(" and (exists (select 1 from user_org_roles uor")
                    .append(" where uor.user_id = u.id and ")
                    .append(scope.inList("uor.org_id"))
                    .append(") or (u.status = 'ACTIVE' and ")
                    .append(OrgMembershipSql.memberOfOrgLinkedWorkspace("u.id", scope))
                    .append("))");
            params.addAll(scope.orgIds());
            params.addAll(scope.orgIds());
            params.addAll(scope.orgIds());
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
                select u.id, u.public_id, u.email, u.name, u.role, u.status, u.created_at
                """ + base + where
                + " order by " + resolveOrder(sort) + " limit ? offset ?",
                (rs, rowNum) -> mapRow(rs), pageParams.toArray());
        List<Long> pageUserIds = rows.stream().map(Row::id).toList();
        // Real 2FA state: one set-membership query over the page's user ids
        // (the console mfa-reset button keys off this).
        Set<Long> enrolled = rows.isEmpty() ? Set.of()
                : Set.copyOf(userMfaRepository.findEnrolledUserIds(pageUserIds));
        // One query for the whole page rather than one per row.
        Map<Long, List<ManagedOrgResponse>> managedOrgs =
                managedOrgQueryService.byUser(pageUserIds);
        List<UserAdminViewResponse> content = rows.stream()
                .map(v -> new UserAdminViewResponse(v.publicId(), v.email(), v.name(), v.role(),
                        managedOrgs.getOrDefault(v.id(), List.of()), v.status(),
                        enrolled.contains(v.id()), v.createdAt()))
                .toList();
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }

    @Transactional(readOnly = true)
    public UserAdminDetailResponse getUser(AuthenticatedUser actor, UUID userId) {
        User user = userRepository.findByPublicId(userId)
                .orElseThrow(AdminUserQueryService::userNotFound);
        if (!StudentSupportPolicy.canReadUser(actor, user.getRole())) {
            // Withheld from the list, so withheld here too, or the id alone
            // reopens it. 404 as an organisation outside the actor's answers.
            throw userNotFound();
        }

        List<WorkspaceMember> liveMemberships = workspaceMemberRepository.findWithWorkspaceByUserId(user.getId()).stream()
                .filter(member -> member.getWorkspace().getDeletedAt() == null)
                .toList();
        List<Long> workspaceIds = liveMemberships.stream().map(m -> m.getWorkspace().getId()).toList();
        Map<Long, List<UUID>> vmOrgs = vmOrgsByWorkspace(workspaceIds);
        List<AdminMembershipResponse> memberships = liveMemberships.stream()
                .map(m -> new AdminMembershipResponse(m.getWorkspace().getPublicId(),
                        m.getWorkspace().getName(), m.getWorkspace().getKind(), m.getRole(),
                        vmOrgs.getOrDefault(m.getWorkspace().getId(), List.of())))
                .toList();
        int activeVmCount = workspaceIds.isEmpty() ? 0
                : (int) vmRepository.countActiveByWorkspaceIdIn(workspaceIds, VmStatus.DELETED);

        List<UserStatusChangeResponse> statusChanges = mapStatusChanges(
                userStatusChangeRepository.findByUserIdOrderByChangedAtDescIdDesc(user.getId()),
                actor.role().isOrgTier());

        return new UserAdminDetailResponse(user.getPublicId(), user.getEmail(), user.getName(),
                user.getRole(), managedOrgQueryService.of(user.getId()), user.getStatus(),
                userMfaRepository.isEnrolled(user.getId()),
                user.getCreatedAt(),
                user.getWithdrawnAt(), user.getDisabledAt(), user.getDisabledReason(),
                memberships, activeVmCount, statusChanges,
                user.getPosition(), user.getStudentNo(), user.getDepartmentCode(),
                profileOptionsService.departmentName(user.getDepartmentCode()), user.getDepartmentOther(),
                new kr.ac.pusan.pickle.admin.dto.AdminUserSupportCapabilitiesResponse(
                        StudentSupportPolicy.canReadUser(actor, user.getRole()),
                        StudentSupportPolicy.canCorrectProfile(actor, user.getRole()),
                        StudentSupportPolicy.canEnableAccount(actor)));
    }

    /**
     * The organisations each workspace has live machines in. Derived, since a
     * workspace has no organisation column, and one query for the whole set.
     */
    private Map<Long, List<UUID>> vmOrgsByWorkspace(List<Long> workspaceIds) {
        if (workspaceIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = workspaceIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        Map<Long, List<UUID>> byWorkspace = new java.util.LinkedHashMap<>();
        jdbcTemplate.query("""
                select distinct v.workspace_id, o.public_id
                  from vms v join orgs o on o.id = v.org_id
                 where v.status <> 'DELETED' and v.workspace_id in (""" + placeholders + ")",
                rs -> {
                    byWorkspace.computeIfAbsent(rs.getLong("workspace_id"), k -> new ArrayList<>())
                            .add(rs.getObject("public_id", UUID.class));
                },
                workspaceIds.toArray());
        return byWorkspace;
    }

    /**
     * Resolves each transition's actor in one batch: id, email and name.
     *
     * <p><b>A system-tier actor's public id is withheld from the org tier</b>,
     * which is not answered for those accounts at all and would use the id to
     * reopen one. The name and address stay: an administrator fielding a
     * question about a suspended account needs to know who acted. Most actors
     * here are system-tier, since disable and enable are SYS_ADMIN-only, but a
     * withdrawal records the holder itself and that id is not withheld.
     */
    private List<UserStatusChangeResponse> mapStatusChanges(List<UserStatusChange> changes,
            boolean orgTierReader) {
        List<Long> actorIds = changes.stream().map(UserStatusChange::getActorId)
                .filter(id -> id != null).distinct().toList();
        Map<Long, User> actors = userRepository.findAllById(actorIds).stream()
                .collect(Collectors.toMap(User::getId, java.util.function.Function.identity()));
        return changes.stream()
                .map(change -> {
                    User actor = actors.get(change.getActorId());
                    boolean withholdId = actor != null && orgTierReader && actor.getRole().isSysTier();
                    return new UserStatusChangeResponse(change.getFromStatus(), change.getToStatus(),
                            actor == null || withholdId ? null : actor.getPublicId(),
                            actor == null ? null : actor.getEmail(),
                            actor == null ? null : actor.getName(),
                            change.getReason(), change.getChangedAt());
                })
                .toList();
    }

    /** One list row: the internal key the 2FA join needs, plus the public view. */
    private record Row(long id, UUID publicId, String email, String name, UserRole role,
            UserStatus status, java.time.Instant createdAt) {
    }

    private static Row mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getLong("id"), rs.getObject("public_id", UUID.class),
                rs.getString("email"), rs.getString("name"),
                UserRole.valueOf(rs.getString("role")),
                UserStatus.valueOf(rs.getString("status")),
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

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static ApiException userNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "사용자를 찾을 수 없습니다", "해당 ID의 사용자가 존재하지 않습니다.");
    }

    /** Optional derived institution-membership filter, independent of support authorization. */
    private OrgScope scopeOrgId(AuthenticatedUser actor, UUID orgId) {
        Long requested = orgId == null ? null : jdbcTemplate.query(
                "select id from orgs where public_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, orgId);
        if (orgId != null && requested == null) {
            return OrgScope.nothing();
        }
        return OrgScope.of(requested);
    }
}
