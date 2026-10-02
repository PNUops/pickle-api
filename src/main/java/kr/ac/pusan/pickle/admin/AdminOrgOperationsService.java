package kr.ac.pusan.pickle.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminOrgOperationsResponse;
import kr.ac.pusan.pickle.admin.dto.OrgOperationsPreviewResponse;
import kr.ac.pusan.pickle.admin.dto.SaveOrgOperationsRequest;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.orgs.OrgAdministrationLock;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.jspecify.annotations.Nullable;

@Service
public class AdminOrgOperationsService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final AdminOrgOperationsQueryService query;
    private final OrgAdministrationLock lock;
    private final AuditService audit;

    public AdminOrgOperationsService(JdbcTemplate jdbc, AdminOrgOperationsQueryService query,
            OrgAdministrationLock lock, AuditService audit) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.query = query;
        this.lock = lock;
        this.audit = audit;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void initializeNewOrg(long orgId) {
        jdbc.update("update orgs set request_mail_mode = 'DESIGNATED' where id = ?", orgId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OrgOperationsPreviewResponse preview(AuthenticatedUser actor, UUID orgId,
            SaveOrgOperationsRequest request) {
        UserRole authority = requireWrite(actor, orgId);
        AdminOrgOperationsResponse before = query.get(actor, orgId, null);
        return evaluate(actor, authority, before, request);
    }

    @Transactional
    public AdminOrgOperationsResponse save(AuthenticatedUser actor, UUID orgId,
            SaveOrgOperationsRequest request, String ip) {
        return apply(actor, orgId, request, ip, null);
    }

    private AdminOrgOperationsResponse apply(AuthenticatedUser actor, UUID orgId,
            SaveOrgOperationsRequest request, String ip, @Nullable UUID changedUserId) {
        lock.acquire();
        UserRole authority = requireWrite(actor, orgId);
        AdminOrgOperationsResponse before = query.get(actor, orgId, null);
        OrgOperationsPreviewResponse preview = evaluate(actor, authority, before, request);
        if (preview.createsStaffVacancy() && !(authority == UserRole.SYS_ADMIN
                && request.allowVacancy() && orgId.equals(request.confirmedOrgId())
                && request.reason() != null && !request.reason().isBlank())) {
            throw conflict("마지막 활성 기관 관리자가 없어집니다. 시스템 관리자의 사유와 대상 확인이 필요합니다.");
        }
        long internalOrgId = orgInternalId(orgId);
        Set<UUID> affected = new HashSet<>();
        before.members().forEach(member -> affected.add(member.userId()));
        request.members().forEach(member -> affected.add(member.userId()));
        Map<UUID, Account> accounts = accounts(affected);
        Set<UUID> desired = new HashSet<>();
        request.members().forEach(member -> desired.add(member.userId()));
        for (AdminOrgOperationsResponse.AdminOrgRoleMemberView member : before.members()) {
            if (!desired.contains(member.userId())) {
                jdbc.update("delete from user_org_roles where org_id = ? and user_id = ?",
                        internalOrgId, accounts.get(member.userId()).id());
            }
        }
        for (SaveOrgOperationsRequest.Assignment member : request.members()) {
            jdbc.update("""
                    insert into user_org_roles (user_id, org_id, role, request_mail)
                    values (?, ?, ?::user_role, ?)
                    on conflict (user_id, org_id) do update
                        set role = excluded.role, request_mail = excluded.request_mail
                    """, accounts.get(member.userId()).id(), internalOrgId,
                    member.role().name(), member.requestMail());
        }
        for (Account account : accounts.values()) {
            jdbc.update("""
                    update users u set row_revision = u.row_revision +
                        case when u.role <> coalesce((select r.role from user_org_roles r
                            where r.user_id = u.id order by case r.role when 'ORG_ADMIN' then 3 when 'ORG_MANAGER' then 2 else 1 end desc limit 1), 'USER'::user_role)
                        then 1 else 0 end,
                        token_version = u.token_version +
                        case when u.role <> coalesce((select r.role from user_org_roles r
                            where r.user_id = u.id order by case r.role when 'ORG_ADMIN' then 3 when 'ORG_MANAGER' then 2 else 1 end desc limit 1), 'USER'::user_role)
                        then 1 else 0 end,
                        role = coalesce((select r.role from user_org_roles r where r.user_id = u.id
                            order by case r.role when 'ORG_ADMIN' then 3 when 'ORG_MANAGER' then 2 else 1 end desc limit 1), 'USER'::user_role)
                    where u.id = ?
                    """, account.id());
        }
        jdbc.update("update orgs set admin_revision = admin_revision + 1, request_mail_mode = ? where id = ?",
                request.mailMode() == null ? null : request.mailMode().name(), internalOrgId);
        AdminOrgOperationsResponse after = query.get(actor, orgId, null);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", auditSnapshot(before));
        detail.put("after", auditSnapshot(after));
        detail.put("reason", request.reason() == null ? "" : request.reason());
        detail.put("vacancyOverride", preview.createsStaffVacancy());
        if (changedUserId == null) {
            audit.recordOrgChange(actor.id(), authority.name(), "org.operations_update", orgId,
                    after.org().name(), detail, ip);
        } else {
            detail.put("previousRole", accounts.get(changedUserId).role().name());
            detail.put("role", jdbc.queryForObject("select role::text from users where public_id = ?", String.class, changedUserId));
            detail.put("orgId", orgId);
            audit.recordOrgUserChange(actor.id(), authority.name(), orgId, after.org().name(), changedUserId, detail, ip);
        }
        return after;
    }

    private OrgOperationsPreviewResponse evaluate(AuthenticatedUser actor, UserRole authority,
            AdminOrgOperationsResponse before, SaveOrgOperationsRequest request) {
        if (request.expectedRevision() == null || request.expectedRevision() != before.revision()) {
            throw conflict("다른 변경으로 설정이 갱신됐습니다. 최신 명단을 다시 불러와 변경안을 확인해 주세요.");
        }
        if (before.mailMode() != null && request.mailMode() == null) {
            throw invalid("mailMode", "명시적으로 저장한 수신 방식은 기존 대체 방식으로 되돌릴 수 없습니다.");
        }
        Set<UUID> ids = new HashSet<>();
        for (SaveOrgOperationsRequest.Assignment member : request.members()) {
            if (!ids.add(member.userId())) throw invalid("members", "같은 계정을 중복 지정할 수 없습니다.");
            if (!member.role().isOrgTier()) throw invalid("members", "기관 역할만 지정할 수 있습니다.");
            if (member.requestMail() && member.role() == UserRole.ORG_VIEWER) {
                throw invalid("members", "신청을 승인할 수 있는 기관 계정만 메일 수신자로 지정할 수 있습니다.");
            }
        }
        Map<UUID, Account> accounts = accounts(ids);
        if (accounts.size() != ids.size()) throw invalid("members", "존재하지 않는 계정이 포함돼 있습니다.");
        for (Account account : accounts.values()) {
            if (account.role().isSysTier()) throw forbiddenUser();
        }
        if (authority.isOrgTier()) {
            UserRole previous = before.members().stream().filter(member -> member.userId().equals(actor.publicId()))
                    .map(AdminOrgOperationsResponse.AdminOrgRoleMemberView::role).findFirst().orElse(null);
            UserRole next = request.members().stream().filter(member -> member.userId().equals(actor.publicId()))
                    .map(SaveOrgOperationsRequest.Assignment::role).findFirst().orElse(null);
            if (previous != next) throw forbiddenUser();
        }
        List<AdminOrgOperationsQueryService.RoleRow> rows = request.members().stream().map(member -> {
            Account account = accounts.get(member.userId());
            return new AdminOrgOperationsQueryService.RoleRow(member.userId(), account.name(), account.email(),
                    account.status(), member.role(), member.requestMail());
        }).toList();
        AdminOrgOperationsResponse after = AdminOrgOperationsQueryService.evaluate(before.org(),
                before.revision() + 1, request.mailMode(), rows, null);
        List<String> warnings = new ArrayList<>();
        if (after.currentMailRecipientCount() == 0) warnings.add("신청 메일을 받을 기관 수신자가 0명입니다.");
        boolean vacancy = (before.activeAdminCount() > 0 && after.activeAdminCount() == 0)
                || (before.activeApproverCount() > 0 && after.activeApproverCount() == 0);
        if (vacancy) warnings.add("마지막 활성 기관 관리자 또는 승인자가 없어집니다.");
        return new OrgOperationsPreviewResponse(before, after, vacancy, warnings);
    }

    @Transactional
    public AdminOrgOperationsResponse saveSingle(AuthenticatedUser actor, UUID orgId, UUID userId,
            @Nullable UserRole role, @Nullable Boolean requestMail, boolean revoke,
            long expectedRevision, String ip) {
        lock.acquire();
        requireWrite(actor, orgId);
        if (role != null && userId.equals(actor.publicId())) {
            throw forbiddenUser();
        }
        var before = query.get(actor, orgId, null);
        List<SaveOrgOperationsRequest.Assignment> members = new ArrayList<>();
        boolean found = false;
        for (var member : before.members()) {
            if (member.userId().equals(userId)) {
                found = true;
                if (revoke) continue;
                if (Boolean.TRUE.equals(requestMail) && member.role() == UserRole.ORG_VIEWER) {
                    throw invalid("enabled", "신청을 승인할 수 있는 역할만 메일 수신자로 지정할 수 있습니다.");
                }
                UserRole nextRole = role == null ? member.role() : role;
                members.add(new SaveOrgOperationsRequest.Assignment(userId, nextRole,
                        nextRole == UserRole.ORG_VIEWER ? false
                                : requestMail == null ? member.requestMail() : requestMail));
            } else {
                members.add(new SaveOrgOperationsRequest.Assignment(member.userId(), member.role(), member.requestMail()));
            }
        }
        if (!found && role != null) members.add(new SaveOrgOperationsRequest.Assignment(userId, role, false));
        if (!found && role == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                    "기관 역할을 찾을 수 없습니다", "해당 계정의 기관 역할이 없습니다.");
        }
        return apply(actor, orgId, new SaveOrgOperationsRequest(expectedRevision, members,
                before.mailMode(), "기관 역할 또는 수신 설정 단건 변경", false, null), ip, userId);
    }

    private UserRole requireWrite(AuthenticatedUser actor, UUID orgId) {
        List<String> allowed = jdbc.queryForList("""
                select u.role::text from users u where u.id = ? and u.status = 'ACTIVE'
                  and (u.role = 'SYS_ADMIN' or exists (select 1 from user_org_roles r
                    join orgs o on o.id = r.org_id where r.user_id = u.id
                      and o.public_id = ? and r.role = 'ORG_ADMIN'))
                """, String.class, actor.id(), orgId);
        if (allowed.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                    "기관을 찾을 수 없습니다", "변경할 수 있는 기관을 찾을 수 없습니다.");
        }
        return UserRole.valueOf(allowed.getFirst());
    }

    private Map<UUID, Account> accounts(Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        List<Account> rows = named.query("""
                select id, public_id, name, email, status::text, role::text from users
                 where public_id in (:ids)
                """, Map.of("ids", ids), (rs, index) -> new Account(rs.getLong("id"),
                        rs.getObject("public_id", UUID.class), rs.getString("name"), rs.getString("email"),
                        UserStatus.valueOf(rs.getString("status")), UserRole.valueOf(rs.getString("role"))));
        Map<UUID, Account> result = new LinkedHashMap<>();
        rows.forEach(account -> result.put(account.publicId(), account));
        return result;
    }

    private long orgInternalId(UUID orgId) {
        return jdbc.queryForObject("select id from orgs where public_id = ?", Long.class, orgId);
    }

    private static Map<String, Object> auditSnapshot(AdminOrgOperationsResponse value) {
        return Map.of("revision", value.revision(), "mailMode",
                value.mailMode() == null ? "LEGACY" : value.mailMode().name(), "members",
                value.members().stream().map(member -> Map.of("userId", member.userId(),
                        "role", member.role().name(), "requestMail", member.requestMail())).toList());
    }

    private static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, kr.ac.pusan.pickle.common.error.ErrorCodes.ORG_OPERATIONS_CONFLICT, "기관 변경을 저장할 수 없습니다", detail);
    }

    private static ApiException invalid(String field, String detail) {
        return ApiException.validationFailed(List.of(new FieldValidationError(field, detail)));
    }

    private static ApiException forbiddenUser() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                "계정 역할을 변경할 수 없습니다", "본인이나 시스템 계정의 기관 역할은 변경할 수 없습니다.");
    }

    private record Account(long id, UUID publicId, String name, String email, UserStatus status, UserRole role) {
    }
}
