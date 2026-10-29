package kr.ac.pusan.pickle.admin;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.AdminOrgOperationsResponse;
import kr.ac.pusan.pickle.admin.dto.OrgOperationsPreviewResponse;
import kr.ac.pusan.pickle.admin.dto.SaveOrgOperationsRequest;
import kr.ac.pusan.pickle.admin.dto.SaveOrgRequestMailSettingsRequest;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.orgs.OrgAdministrationLock;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignment;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignmentCodec;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignmentStore;
import kr.ac.pusan.pickle.security.institution.InstitutionAuthority;
import kr.ac.pusan.pickle.security.institution.InstitutionAuthorization;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkGroup;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope;
import kr.ac.pusan.pickle.security.institution.dto.InstitutionPermissionInput;
import kr.ac.pusan.pickle.security.institution.dto.InstitutionPermissionMode;
import kr.ac.pusan.pickle.security.institution.dto.InstitutionWorkspaceScopeInput;
import kr.ac.pusan.pickle.security.institution.dto.InstitutionWorkspaceScopeMode;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.LifecycleActors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** The authorized atomic writer for memberships, explicit authority, and receipt policy. */
@Service
public class AdminOrgOperationsService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final AdminOrgOperationsQueryService query;
    private final OrgAdministrationLock lock;
    private final AuditService audit;
    private final LifecycleActors actors;
    private final InstitutionAuthorization authorization;
    private final InstitutionAssignmentCodec codec = new InstitutionAssignmentCodec();
    private final InstitutionAssignmentStore assignments;
    private final EntityManager entityManager;

    public AdminOrgOperationsService(JdbcTemplate jdbc, AdminOrgOperationsQueryService query,
            OrgAdministrationLock lock, AuditService audit, LifecycleActors actors,
            InstitutionAuthorization authorization, EntityManager entityManager) {
        this.jdbc = jdbc; this.named = new NamedParameterJdbcTemplate(jdbc); this.query = query;
        this.lock = lock; this.audit = audit; this.actors = actors; this.authorization = authorization;
        this.entityManager = entityManager; assignments = new InstitutionAssignmentStore(jdbc, codec);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void initializeNewOrg(long orgId) {
        jdbc.update("update orgs set request_mail_mode='DESIGNATED' where id=?", orgId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OrgOperationsPreviewResponse preview(AuthenticatedUser actor, UUID orgId, SaveOrgOperationsRequest request) {
        AuthenticatedUser current = actors.current(actor);
        requireWork(current, orgId, InstitutionWork.INSTITUTION_DELEGATE);
        return evaluate(current, orgId, request).preview();
    }

    @Transactional
    public AdminOrgOperationsResponse save(AuthenticatedUser actor, UUID orgId, SaveOrgOperationsRequest request, String ip) {
        lock.acquire();
        AuthenticatedUser current = actors.currentAfterAdministrationLock(actor);
        requireWork(current, orgId, InstitutionWork.INSTITUTION_DELEGATE);
        entityManager.flush();
        return apply(current, orgId, request, ip, null);
    }

    private AdminOrgOperationsResponse apply(AuthenticatedUser actor, UUID orgId,
            SaveOrgOperationsRequest request, String ip, @Nullable UUID changedUserId) {
        InstitutionRosterPlan plan = evaluate(actor, orgId, request);
        if (plan.preview().createsStaffVacancy() && !(actor.role() == UserRole.SYS_ADMIN && request.allowVacancy()
                && orgId.equals(request.confirmedOrgId()) && request.reason() != null && !request.reason().isBlank())) {
            throw conflict("마지막 활성 FULL 담당자 또는 신청 결정 담당자가 없어집니다. 시스템 관리자의 사유와 대상 확인이 필요합니다.");
        }
        long org = internalOrg(orgId);
        Set<UUID> desiredIds = plan.rows().stream().map(AdminOrgOperationsQueryService.InstitutionStaffRow::userId)
                .collect(java.util.stream.Collectors.toSet());
        for (var before : plan.before().members()) {
            if (!desiredIds.contains(before.userId())) {
                int changed = jdbc.update("delete from user_org_roles where org_id=? and user_id=? and permission_revision=?",
                        org, plan.accounts().get(before.userId()).id(), request.expectedPermissionRevisions().get(before.userId()));
                if (changed != 1) { throw conflict("담당자의 권한 버전이 달라졌습니다. 명단을 다시 불러와 주세요."); }
            }
        }
        Map<UUID, AdminOrgOperationsResponse.AdminOrgRoleMemberView> previous = new LinkedHashMap<>();
        plan.before().members().forEach(member -> previous.put(member.userId(), member));
        for (var row : plan.rows()) {
            InstitutionAssignment desired = row.assignment();
            var encoded = codec.encode(desired);
            var before = previous.get(row.userId());
            int changed;
            if (before == null) {
                changed = jdbc.update("""
                        insert into user_org_roles(user_id,org_id,role,request_mail,permission_mode,permission_payload,permission_revision)
                        values(?,?,?::user_role,?,?,?::jsonb,nextval('institution_permission_revision_seq'))
                        on conflict(user_id,org_id) do nothing
                        """, plan.accounts().get(row.userId()).id(), org, desired.displayRole().name(), row.requestMail(), encoded.mode(), encoded.payload());
            } else {
                InstitutionAssignment old = assignment(before, orgId);
                boolean same = sameContent(old, desired);
                changed = jdbc.update("""
                        update user_org_roles set role=?::user_role,request_mail=?,permission_mode=?,permission_payload=?::jsonb,
                          permission_revision=case when ? then permission_revision else nextval('institution_permission_revision_seq') end
                         where user_id=? and org_id=? and permission_revision=?
                        """, desired.displayRole().name(), row.requestMail(), encoded.mode(), encoded.payload(), same,
                        plan.accounts().get(row.userId()).id(), org, old.revision());
            }
            if (changed != 1) { throw conflict("담당자의 권한 버전이 달라졌습니다. 명단을 다시 불러와 주세요."); }
        }
        for (InstitutionRosterAccount account : plan.accounts().values()) {
            jdbc.update("""
                    update users u set role=coalesce((select r.role from user_org_roles r where r.user_id=u.id
                       order by case r.role when 'ORG_ADMIN' then 3 when 'ORG_MANAGER' then 2 else 1 end desc limit 1),'USER'::user_role),
                       row_revision=u.row_revision+1
                     where u.id=? and u.role is distinct from coalesce((select r.role from user_org_roles r where r.user_id=u.id
                       order by case r.role when 'ORG_ADMIN' then 3 when 'ORG_MANAGER' then 2 else 1 end desc limit 1),'USER'::user_role)
                    """, account.id());
        }
        int changed = jdbc.update("update orgs set admin_revision=admin_revision+1,request_mail_mode=? where id=? and admin_revision=?",
                request.mailMode() == null ? null : request.mailMode().name(), org, request.expectedRevision());
        if (changed != 1) { throw conflict("다른 변경으로 기관 설정이 갱신됐습니다. 다시 불러와 주세요."); }
        entityManager.clear();
        AdminOrgOperationsResponse after = query.readForCommand(orgId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", auditSnapshot(plan.before())); detail.put("after", auditSnapshot(after));
        detail.put("reason", request.reason() == null ? "" : request.reason());
        detail.put("vacancyOverride", plan.preview().createsStaffVacancy());
        detail.put("authorityFingerprint", actor.institutionContext().authority().map(InstitutionAuthority::fingerprint).orElse("SYS"));
        if (changedUserId == null) {
            audit.recordOrgChange(actor.id(), actor.role().name(), "org.operations_update", orgId, after.org().name(), detail, ip);
        } else {
            detail.put("previousRole", plan.accounts().get(changedUserId).role().name());
            detail.put("role", jdbc.queryForObject("select role::text from users where public_id=?", String.class, changedUserId));
            detail.put("orgId", orgId);
            audit.recordOrgUserChange(actor.id(), actor.role().name(), orgId, after.org().name(), changedUserId, detail, ip);
        }
        return query.get(actor, orgId, null);
    }

    private InstitutionRosterPlan evaluate(AuthenticatedUser actor, UUID orgId, SaveOrgOperationsRequest request) {
        AdminOrgOperationsResponse before = query.readForCommand(orgId);
        if (request.expectedRevision() == null || request.expectedRevision() != before.revision()) { throw conflict("설정 버전이 달라졌습니다. 명단을 다시 불러와 주세요."); }
        Map<UUID, Long> revisions = new LinkedHashMap<>();
        before.members().forEach(member -> revisions.put(member.userId(), member.permission().revision()));
        if (!revisions.equals(request.expectedPermissionRevisions())) { throw conflict("권한 부여의 원천 버전이 달라졌습니다. 명단을 다시 불러와 주세요."); }
        if (before.mailMode() != null && request.mailMode() == null) { throw invalid("mailMode", "명시한 수신 방식을 기존 대체 방식으로 되돌릴 수 없습니다."); }
        Set<UUID> ids = new HashSet<>(revisions.keySet());
        Set<UUID> desiredIds = new HashSet<>();
        for (var member : request.members()) {
            if (!desiredIds.add(member.userId())) { throw invalid("members", "같은 계정을 중복 지정할 수 없습니다."); }
            ids.add(member.userId());
        }
        Map<UUID, InstitutionRosterAccount> accounts = accounts(ids);
        if (accounts.size() != ids.size()) { throw invalid("members", "존재하지 않는 계정이 포함돼 있습니다."); }
        Map<UUID, InstitutionAssignment> old = new LinkedHashMap<>();
        before.members().forEach(member -> old.put(member.userId(), assignment(member, orgId)));
        List<AdminOrgOperationsQueryService.InstitutionStaffRow> rows = new ArrayList<>();
        for (var member : request.members()) {
            var account = accounts.get(member.userId());
            if (account.role().isSysTier()) { throw forbiddenUser(); }
            InstitutionAuthority whole = assignments.load(member.userId());
            if (whole.displayRole().orElse(UserRole.USER) != account.role()) { throw AdminOrgOperationsQueryService.invalidState(); }
            InstitutionAssignment desired = member.permission().toAssignment(member.userId(), orgId);
            InstitutionAssignment previous = old.get(member.userId());
            long expected = previous == null ? 0 : previous.revision();
            if (desired.revision() != expected) { throw conflict("담당자의 권한 버전이 달라졌습니다. 명단을 다시 불러와 주세요."); }
            validateScopes(desired, previous);
            if (actor.role().isOrgTier() && member.userId().equals(actor.publicId())
                    && (previous == null || !sameContent(previous, desired))) { throw forbiddenUser(); }
            if (member.requestMail() && !hasDecisionWork(desired)) { throw invalid("members", "신청 결정 업무가 없는 담당자는 메일 수신자로 지정할 수 없습니다."); }
            rows.add(new AdminOrgOperationsQueryService.InstitutionStaffRow(member.userId(), account.name(), account.email(),
                    account.status(), desired, member.requestMail(), desired.displayRole()));
        }
        if (actor.role().isOrgTier() && old.containsKey(actor.publicId()) && !desiredIds.contains(actor.publicId())) { throw forbiddenUser(); }
        AdminOrgOperationsResponse after = query.evaluate(before.org(), before.revision()+1, request.mailMode(), rows, null, null);
        List<String> warnings = new ArrayList<>();
        if (Integer.valueOf(0).equals(after.currentMailRecipientCount())) { warnings.add("현재 신청 메일을 받을 수 있는 담당자가 0명입니다. 대체 수신자를 자동으로 고르지 않습니다."); }
        boolean vacancy = before.activeAdminCount()>0 && after.activeAdminCount()==0 || before.activeApproverCount()>0 && after.activeApproverCount()==0;
        if (vacancy) { warnings.add("마지막 활성 FULL 담당자 또는 실제 신청 결정 담당자가 없어집니다."); }
        var afterStaffing = query.staffing(rows);
        var beforeStaffing = query.staffing(query.rows(internalOrg(orgId)));
        afterStaffing.decidersByKind().forEach((kind, count) -> {
            if (count == 0 && beforeStaffing.decidersByKind().get(kind) > 0) {
                warnings.add(kind.name() + ": 현재 신청을 결정할 활성 담당자가 없어집니다. 다른 종류의 결정 업무로 대체하지 않습니다.");
            }
        });
        for (var member : before.members()) {
            if (member.permission().mode() == InstitutionPermissionMode.FULL
                    && rows.stream().noneMatch(row -> row.userId().equals(member.userId()) && row.assignment().mode() == InstitutionAssignment.Mode.FULL)) {
                boolean remaining = assignments.load(member.userId()).assignments().stream().anyMatch(source ->
                        !source.institutionId().equals(orgId) && source.mode() == InstitutionAssignment.Mode.FULL);
                warnings.add(member.name() + (remaining ? ": 이 기관의 FULL이 사라져도 다른 FULL 출처의 전역 개별 키 비용 읽기는 유지됩니다."
                        : ": 마지막 FULL 출처가 사라져 전역 개별 키 비용 읽기도 없어집니다."));
            }
        }
        return new InstitutionRosterPlan(before, rows, accounts, new OrgOperationsPreviewResponse(before, after, vacancy, warnings));
    }

    @Transactional
    public AdminOrgOperationsResponse saveSingle(AuthenticatedUser actor, UUID orgId, UUID userId,
            @Nullable InstitutionPermissionInput permission, boolean revoke, long expectedRevision,
            long expectedPermissionRevision, String ip) {
        lock.acquire();
        AuthenticatedUser current = actors.currentAfterAdministrationLock(actor);
        requireWork(current, orgId, InstitutionWork.INSTITUTION_DELEGATE);
        if (userId.equals(current.publicId())) { throw forbiddenUser(); }
        var before = query.readForCommand(orgId);
        Map<UUID, Long> revisions = new LinkedHashMap<>();
        List<SaveOrgOperationsRequest.Assignment> members = new ArrayList<>();
        boolean found = false;
        for (var member : before.members()) {
            revisions.put(member.userId(), member.permission().revision());
            if (member.userId().equals(userId)) {
                found = true;
                if (expectedPermissionRevision != member.permission().revision()) { throw conflict("권한 부여 버전이 달라졌습니다."); }
                if (!revoke) { members.add(new SaveOrgOperationsRequest.Assignment(userId, permission, Boolean.TRUE.equals(member.requestMail())
                        && hasDecisionWork(permission.toAssignment(userId, orgId)))); }
            } else { members.add(new SaveOrgOperationsRequest.Assignment(member.userId(), input(member, orgId), Boolean.TRUE.equals(member.requestMail()))); }
        }
        if (!found && !revoke && permission != null && expectedPermissionRevision == 0) {
            members.add(new SaveOrgOperationsRequest.Assignment(userId, permission, false));
        } else if (!found) { throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND, "기관 권한을 찾을 수 없습니다", "현재 기관 권한 부여가 없습니다."); }
        entityManager.flush();
        return apply(current, orgId, new SaveOrgOperationsRequest(expectedRevision, revisions, members, before.mailMode(),
                "기관 권한 단건 변경", false, null), ip, userId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OrgOperationsPreviewResponse previewMail(AuthenticatedUser actor,UUID orgId,SaveOrgRequestMailSettingsRequest request) {
        return previewMail(actor,orgId,request,null,null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OrgOperationsPreviewResponse previewMail(AuthenticatedUser actor,UUID orgId,SaveOrgRequestMailSettingsRequest request,
            @Nullable ResourceType type,@Nullable UUID workspaceId) {
        AuthenticatedUser current=actors.current(actor);
        requireWork(current,orgId,InstitutionWork.INSTITUTION_REQUEST_MAIL_CONFIGURE);
        var before=query.readForCommand(orgId);
        var after=query.withSelection(mailPlan(before,orgId,request),type,workspaceId);
        return new OrgOperationsPreviewResponse(query.get(current,orgId,null,type,workspaceId),query.projectFor(current,after),false,
                Integer.valueOf(0).equals(after.currentMailRecipientCount()) ? List.of("이 대상의 메일 수신자가 0명입니다. 대체 수신자를 자동으로 고르지 않습니다.") : List.of());
    }

    @Transactional
    public AdminOrgOperationsResponse saveMail(AuthenticatedUser actor, UUID orgId, SaveOrgRequestMailSettingsRequest request, String ip) {
        lock.acquire();
        AuthenticatedUser current = actors.currentAfterAdministrationLock(actor);
        requireWork(current, orgId, InstitutionWork.INSTITUTION_REQUEST_MAIL_CONFIGURE);
        var before = query.readForCommand(orgId);
        mailPlan(before, orgId, request);
        long org = internalOrg(orgId);
        for (var member : before.members()) {
            jdbc.update("update user_org_roles set request_mail=? where org_id=? and user_id=(select id from users where public_id=?)",
                    request.recipientIds().contains(member.userId()), org, member.userId());
        }
        int updated = jdbc.update("update orgs set admin_revision=admin_revision+1,request_mail_mode=? where id=? and admin_revision=?",
                request.mailMode().name(), org, request.expectedRevision());
        if (updated != 1) { throw conflict("설정이 갱신됐습니다. 다시 불러와 주세요."); }
        entityManager.clear();
        var after = query.readForCommand(orgId);
        audit.recordOrgChange(current.id(), current.role().name(), "org.request_mail_update", orgId, after.org().name(),
                Map.of("before", auditSnapshot(before), "after", auditSnapshot(after), "reason", request.reason()==null ? "" : request.reason()), ip);
        return query.get(current, orgId, null);
    }

    @Transactional
    public AdminOrgOperationsResponse saveSingleMail(AuthenticatedUser actor, UUID orgId, UUID userId,
            boolean enabled, long expectedRevision, String ip) {
        lock.acquire();
        AuthenticatedUser current = actors.currentAfterAdministrationLock(actor);
        requireWork(current, orgId, InstitutionWork.INSTITUTION_REQUEST_MAIL_CONFIGURE);
        var before = query.readForCommand(orgId);
        if (before.members().stream().noneMatch(member -> member.userId().equals(userId))) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND, "기관 권한을 찾을 수 없습니다", "해당 기관의 담당자가 아닙니다.");
        }
        Set<UUID> recipients = before.members().stream().filter(member -> Boolean.TRUE.equals(member.requestMail()))
                .map(AdminOrgOperationsResponse.AdminOrgRoleMemberView::userId).collect(java.util.stream.Collectors.toSet());
        if (enabled) { recipients.add(userId); } else { recipients.remove(userId); }
        return saveMail(current, orgId, new SaveOrgRequestMailSettingsRequest(expectedRevision,
                before.mailMode()==null ? kr.ac.pusan.pickle.orgs.RequestMailMode.DESIGNATED : before.mailMode(), recipients, "기관 메일 수신 설정 단건 변경"), ip);
    }

    private AdminOrgOperationsResponse mailPlan(AdminOrgOperationsResponse before, UUID orgId, SaveOrgRequestMailSettingsRequest request) {
        if (request.expectedRevision()==null || before.revision()!=request.expectedRevision()) { throw conflict("설정 버전이 달라졌습니다. 다시 불러와 주세요."); }
        Set<UUID> members = before.members().stream().map(AdminOrgOperationsResponse.AdminOrgRoleMemberView::userId).collect(java.util.stream.Collectors.toSet());
        if (!members.containsAll(request.recipientIds())) { throw invalid("recipientIds", "현재 기관 담당자만 선택할 수 있습니다."); }
        List<AdminOrgOperationsQueryService.InstitutionStaffRow> rows = new ArrayList<>();
        for (var member : before.members()) {
            var assignment = assignment(member, orgId);
            boolean mail = request.recipientIds().contains(member.userId());
            if (mail && !hasDecisionWork(assignment)) { throw invalid("recipientIds", "신청 결정 업무를 가진 담당자만 선택할 수 있습니다."); }
            rows.add(new AdminOrgOperationsQueryService.InstitutionStaffRow(member.userId(), member.name(), member.email(),
                    member.status(), assignment, mail, member.role()));
        }
        return query.evaluate(before.org(), before.revision()+1, request.mailMode(), rows, null, null);
    }

    private void requireWork(AuthenticatedUser actor, UUID orgId, InstitutionWork work) {
        if (actor.role()==UserRole.SYS_ADMIN) { return; }
        long internal = internalOrg(orgId);
        boolean allowed = authorization.institutionTarget(internal).map(target -> authorization.decision(actor, work, target).allowed()).orElse(false);
        if (!allowed) { throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND, "기관을 찾을 수 없습니다", "변경할 수 있는 기관을 찾을 수 없습니다."); }
    }

    private void validateScopes(InstitutionAssignment desired, @Nullable InstitutionAssignment previous) {
        for (var entry : desired.scopes().entrySet()) {
            if (entry.getValue().mode()==InstitutionWorkspaceScope.Mode.ALL) { continue; }
            Set<UUID> retained = previous==null || !previous.scopes().containsKey(entry.getKey()) ? Set.of() : previous.scopes().get(entry.getKey()).workspaceIds();
            for (UUID workspace : entry.getValue().workspaceIds()) {
                if (!retained.contains(workspace) && !Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from workspaces where public_id=? and deleted_at is null)", Boolean.class, workspace))) {
                    throw invalid("permission.scopes", "새로 지정한 워크스페이스가 없거나 삭제됐습니다. 기존에 보관한 범위는 전체로 바꾸지 않습니다.");
                }
            }
        }
    }

    private Map<UUID, InstitutionRosterAccount> accounts(Set<UUID> ids) {
        if (ids.isEmpty()) { return Map.of(); }
        var rows = named.query("select id,public_id,name,email,status::text,role::text from users where public_id in (:ids) order by id",
                Map.of("ids", ids), (rs, index) -> new InstitutionRosterAccount(rs.getLong("id"),rs.getObject("public_id",UUID.class),
                        rs.getString("name"),rs.getString("email"),UserStatus.valueOf(rs.getString("status")),UserRole.valueOf(rs.getString("role"))));
        Map<UUID, InstitutionRosterAccount> result = new LinkedHashMap<>(); rows.forEach(row -> result.put(row.publicId(),row)); return result;
    }

    private long internalOrg(UUID id) {
        return jdbc.query("select id from orgs where public_id=?", (rs,index)->rs.getLong(1),id).stream().findFirst()
                .orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,ErrorCodes.RESOURCE_NOT_FOUND,"기관을 찾을 수 없습니다","해당 기관이 없습니다."));
    }
    private static InstitutionAssignment assignment(AdminOrgOperationsResponse.AdminOrgRoleMemberView member, UUID org) {
        return input(member,org).toAssignment(member.userId(),org);
    }
    private static InstitutionPermissionInput input(AdminOrgOperationsResponse.AdminOrgRoleMemberView member, UUID org) {
        var permission = member.permission();
        if (permission==null) { throw AdminOrgOperationsQueryService.invalidState(); }
        Map<InstitutionWorkGroup,InstitutionWorkspaceScopeInput> scopes = new LinkedHashMap<>();
        permission.scopes().forEach((group,scope)->scopes.put(group,new InstitutionWorkspaceScopeInput(
                InstitutionWorkspaceScopeMode.valueOf(scope.mode().name()),scope.workspaceIds())));
        return new InstitutionPermissionInput(permission.mode(),permission.work(),scopes,permission.revision());
    }
    private static boolean sameContent(InstitutionAssignment a, InstitutionAssignment b) {
        return a.accountId().equals(b.accountId()) && a.institutionId().equals(b.institutionId())
                && a.mode()==b.mode() && a.work().equals(b.work()) && a.scopes().equals(b.scopes());
    }
    private static boolean hasDecisionWork(InstitutionAssignment assignment) {
        return List.of(InstitutionWork.VM_REQUEST_DECIDE,InstitutionWork.LLM_REQUEST_DECIDE,
                InstitutionWork.DOMAIN_REQUEST_DECIDE,InstitutionWork.GPU_REQUEST_DECIDE).stream().anyMatch(assignment::includes);
    }
    private static Map<String,Object> auditSnapshot(AdminOrgOperationsResponse value) {
        return Map.of("revision",value.revision(),"mailMode",value.mailMode()==null ? "LEGACY" : value.mailMode().name(),
                "members",value.members().stream().map(member -> Map.of("userId",member.userId(),"role",member.role().name(),
                        "permission",member.permission(),"requestMail",Boolean.TRUE.equals(member.requestMail()))).toList());
    }
    private static ApiException conflict(String detail) { return new ApiException(HttpStatus.CONFLICT,ErrorCodes.ORG_OPERATIONS_CONFLICT,"기관 변경을 저장할 수 없습니다",detail); }
    private static ApiException invalid(String field,String detail) { return ApiException.validationFailed(List.of(new FieldValidationError(field,detail))); }
    private static ApiException forbiddenUser() { return new ApiException(HttpStatus.FORBIDDEN,ErrorCodes.ACCESS_DENIED,"기관 권한을 변경할 수 없습니다","본인의 기관 권한이나 시스템 계정의 기관 권한은 변경할 수 없습니다."); }
    private record InstitutionRosterAccount(long id,UUID publicId,String name,String email,UserStatus status,UserRole role) { }
    private record InstitutionRosterPlan(AdminOrgOperationsResponse before,List<AdminOrgOperationsQueryService.InstitutionStaffRow> rows,
            Map<UUID,InstitutionRosterAccount> accounts,OrgOperationsPreviewResponse preview) { }
}
