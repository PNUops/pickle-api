package kr.ac.pusan.pickle.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminOrgOperationsResponse;
import kr.ac.pusan.pickle.admin.dto.OrgDetailResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.orgs.AdminOrgScope;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.orgs.RequestMailMode;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminOrgOperationsQueryService {

    private final OrgRepository orgRepository;
    private final JdbcTemplate jdbc;

    public AdminOrgOperationsQueryService(OrgRepository orgRepository, JdbcTemplate jdbc) {
        this.orgRepository = orgRepository;
        this.jdbc = jdbc;
    }

    /** The roster and recipient interpretation share one database snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminOrgOperationsResponse get(AuthenticatedUser actor, UUID orgId,
            @Nullable UUID requesterId) {
        Org org = orgRepository.findByPublicId(orgId).orElse(null);
        AdminOrgScope.read(actor, orgId, org == null ? null : org.getId());
        if (org == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                    "기관을 찾을 수 없습니다", "해당 기관을 찾을 수 없습니다.");
        }
        return read(org, requesterId);
    }

    @Transactional(readOnly = true)
    public AdminOrgOperationsResponse forSelection(UUID orgId, UUID requesterId) {
        return read(orgRepository.findByPublicId(orgId).orElseThrow(), requesterId);
    }

    private AdminOrgOperationsResponse read(Org org, @Nullable UUID requesterId) {
        Setting setting = jdbc.queryForObject(
                "select admin_revision, request_mail_mode from orgs where id = ?",
                (rs, index) -> new Setting(rs.getLong("admin_revision"),
                        rs.getString("request_mail_mode") == null ? null
                                : RequestMailMode.valueOf(rs.getString("request_mail_mode"))), org.getId());
        List<RoleRow> rows = jdbc.query("""
                select u.public_id, u.name, u.email, u.status::text as status,
                       r.role::text as org_role, r.request_mail
                  from user_org_roles r join users u on u.id = r.user_id
                 where r.org_id = ?
                 order by lower(u.name), lower(u.email), u.public_id
                """, (rs, index) -> new RoleRow(rs.getObject("public_id", UUID.class),
                        rs.getString("name"), rs.getString("email"),
                        UserStatus.valueOf(rs.getString("status")),
                        UserRole.valueOf(rs.getString("org_role")), rs.getBoolean("request_mail")),
                org.getId());
        return evaluate(OrgDetailResponse.from(org), setting.revision(), setting.mode(), rows, requesterId);
    }

    static AdminOrgOperationsResponse evaluate(OrgDetailResponse org, long revision,
            @Nullable RequestMailMode mode, List<RoleRow> rows, @Nullable UUID requesterId) {
        boolean fallback = mode == null
                && rows.stream().noneMatch(row -> eligible(row, requesterId) && row.requestMail());
        List<AdminOrgOperationsResponse.AdminOrgRoleMemberView> members = rows.stream().map(row -> {
            boolean selected = eligible(row, requesterId)
                    && (mode == RequestMailMode.ALL_APPROVERS ||
                        (fallback ? row.role() == UserRole.ORG_ADMIN : row.requestMail()));
            String excluded = selected ? null : exclusionReason(row, requesterId);
            return new AdminOrgOperationsResponse.AdminOrgRoleMemberView(row.userId(), row.name(), row.email(),
                    row.role(), row.status(), row.requestMail(), selected, eligible(row, requesterId),
                    selected ? (fallback ? "LEGACY_ORG_ADMIN"
                            : mode == RequestMailMode.ALL_APPROVERS ? "ALL_APPROVERS" : "DESIGNATED")
                            : null, excluded);
        }).toList();
        int admins = (int) rows.stream().filter(row -> row.status() == UserStatus.ACTIVE
                && row.role() == UserRole.ORG_ADMIN).count();
        int approvers = (int) rows.stream().filter(row -> row.status() == UserStatus.ACTIVE
                && mayApprove(row.role())).count();
        int recipients = (int) members.stream().filter(AdminOrgOperationsResponse.AdminOrgRoleMemberView::currentMailRecipient)
                .count();
        return new AdminOrgOperationsResponse(org, revision, mode, members, admins,
                approvers, recipients, fallback, requesterId, Instant.now());
    }

    private static boolean eligible(RoleRow row, @Nullable UUID requesterId) {
        return row.status() == UserStatus.ACTIVE && mayApprove(row.role())
                && !row.userId().equals(requesterId);
    }

    private static boolean mayApprove(UserRole role) {
        return role == UserRole.ORG_ADMIN || role == UserRole.ORG_MANAGER;
    }

    private static String exclusionReason(RoleRow row, @Nullable UUID requesterId) {
        if (row.userId().equals(requesterId)) return "REQUESTER_EXCLUDED";
        if (row.status() != UserStatus.ACTIVE) return "ACCOUNT_INACTIVE";
        if (!mayApprove(row.role())) return "ROLE_CANNOT_RECEIVE";
        return "NOT_SELECTED";
    }

    record RoleRow(UUID userId, String name, String email, UserStatus status,
            UserRole role, boolean requestMail) {
    }

    private record Setting(long revision, @Nullable RequestMailMode mode) {
    }
}
