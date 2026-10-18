package kr.ac.pusan.pickle.admin;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.AdminOrgOperationsResponse;
import kr.ac.pusan.pickle.admin.dto.OrgDetailResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.orgs.RequestMailMode;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignment;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignmentCodec;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignmentStorageException;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignmentStore;
import kr.ac.pusan.pickle.security.institution.InstitutionAuthority;
import kr.ac.pusan.pickle.security.institution.InstitutionAuthorization;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkTarget;
import kr.ac.pusan.pickle.security.institution.dto.InstitutionPermissionView;
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
    private final InstitutionAuthorization authorization;
    private final InstitutionAssignmentCodec codec = new InstitutionAssignmentCodec();
    private final InstitutionAssignmentStore assignments;

    public AdminOrgOperationsQueryService(OrgRepository orgRepository, JdbcTemplate jdbc,
            InstitutionAuthorization authorization) {
        this.orgRepository = orgRepository;
        this.jdbc = jdbc;
        this.authorization = authorization;
        assignments = new InstitutionAssignmentStore(jdbc, codec);
    }

    /** Staff fields and receipt-policy fields have independent work checks. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminOrgOperationsResponse get(AuthenticatedUser actor, UUID orgId, @Nullable UUID requesterId) {
        return get(actor,orgId,requesterId,null,null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminOrgOperationsResponse get(AuthenticatedUser actor, UUID orgId, @Nullable UUID requesterId,
            @Nullable ResourceType requestType,@Nullable UUID workspaceId) {
        Org org=orgRepository.findByPublicId(orgId).orElseThrow(AdminOrgOperationsQueryService::notFound);
        boolean staff=actor.role().isSysTier() || has(actor,InstitutionWork.INSTITUTION_STAFF_READ,org.getId());
        boolean mail=actor.role().isSysTier() || has(actor,InstitutionWork.INSTITUTION_REQUEST_MAIL_READ,org.getId())
                || has(actor,InstitutionWork.INSTITUTION_REQUEST_MAIL_CONFIGURE,org.getId());
        if (!staff && !mail || !mail && (requestType!=null || workspaceId!=null)) { throw notFound(); }
        var target=selectionTarget(org.getId(),requestType,workspaceId);
        return projectFor(actor,read(org,requesterId,target));
    }

    /** A caller's authorized policy preview may select a real future request target without reading its resource graph. */
    AdminOrgOperationsResponse withSelection(AdminOrgOperationsResponse value,@Nullable ResourceType type,@Nullable UUID workspaceId) {
        long org=jdbc.queryForObject("select id from orgs where public_id=?",Long.class,value.org().id());
        var target=selectionTarget(org,type,workspaceId);
        var rows=value.members().stream().map(member->new InstitutionStaffRow(member.userId(),member.name(),member.email(),member.status(),
                member.permission().toAssignment(member.userId(),value.org().id()),Boolean.TRUE.equals(member.requestMail()),member.role())).toList();
        return evaluate(value.org(),value.revision(),value.mailMode(),rows,value.requesterId(),target);
    }

    private @Nullable InstitutionSelectionTarget selectionTarget(long org,@Nullable ResourceType type,@Nullable UUID workspaceId) {
        if ((type==null)!=(workspaceId==null)) {
            throw ApiException.validationFailed(List.of(new kr.ac.pusan.pickle.common.error.FieldValidationError(
                    "requestType","신청 종류와 workspace ID를 함께 지정해 주세요.")));
        }
        if (type==null) { return null; }
        Long workspace=jdbc.query("select id from workspaces where public_id=? and deleted_at is null",(rs,index)->rs.getLong(1),workspaceId)
                .stream().findFirst().orElseThrow(AdminOrgOperationsQueryService::notFound);
        return new InstitutionSelectionTarget(type,authorization.requestDecisionTarget(type,org,workspace)
                .orElseThrow(AdminOrgOperationsQueryService::notFound));
    }

    AdminOrgOperationsResponse projectFor(AuthenticatedUser actor, AdminOrgOperationsResponse value) {
        Long orgId = jdbc.query("select id from orgs where public_id=?", (rs,index)->rs.getLong(1), value.org().id())
                .stream().findFirst().orElseThrow(AdminOrgOperationsQueryService::notFound);
        boolean staff = actor.role().isSysTier() || has(actor, InstitutionWork.INSTITUTION_STAFF_READ, orgId);
        boolean mail = actor.role().isSysTier() || has(actor, InstitutionWork.INSTITUTION_REQUEST_MAIL_READ, orgId)
                || has(actor, InstitutionWork.INSTITUTION_REQUEST_MAIL_CONFIGURE, orgId);
        if (!staff && !mail) { throw notFound(); }
        return project(value, staff, mail);
    }

    /** Only the authorized command service uses this unprojected current roster. */
    AdminOrgOperationsResponse readForCommand(UUID orgId) {
        return read(orgRepository.findByPublicId(orgId).orElseThrow(AdminOrgOperationsQueryService::notFound), null, null);
    }

    /** The caller holds the administration barrier and supplies the actual request relation. */
    @Transactional(readOnly = true)
    public AdminOrgOperationsResponse forSelection(UUID orgId, UUID requesterId, ResourceType type, long workspaceId) {
        Org org = orgRepository.findByPublicId(orgId).orElseThrow(AdminOrgOperationsQueryService::notFound);
        InstitutionWorkTarget target = authorization.requestDecisionTarget(type, org.getId(), workspaceId)
                .orElseThrow(AdminOrgOperationsQueryService::notFound);
        return read(org, requesterId, new InstitutionSelectionTarget(type, target));
    }

    List<InstitutionStaffRow> rows(long orgId) {
        try {
            return jdbc.query("""
                    select u.public_id,u.name,u.email,u.status::text as status,u.role::text as account_role,
                           r.role::text as org_role,r.request_mail,r.permission_mode,
                           r.permission_payload::text,r.permission_revision,o.public_id as institution_public_id
                      from user_org_roles r join users u on u.id=r.user_id join orgs o on o.id=r.org_id
                     where r.org_id=? order by lower(u.name),lower(u.email),u.public_id
                    """, (rs, index) -> {
                UUID account = rs.getObject("public_id", UUID.class);
                InstitutionAssignment assignment = codec.decode(account, rs.getObject("institution_public_id", UUID.class),
                        UserRole.valueOf(rs.getString("org_role")), rs.getString("permission_mode"),
                        rs.getString("permission_payload"), rs.getObject("permission_revision", Long.class));
                return new InstitutionStaffRow(account, rs.getString("name"), rs.getString("email"),
                        UserStatus.valueOf(rs.getString("status")), assignment, rs.getBoolean("request_mail"),
                        UserRole.valueOf(rs.getString("account_role")));
            }, orgId).stream().peek(row -> {
                InstitutionAuthority whole = assignments.load(row.userId());
                if (whole.displayRole().orElse(null) != row.accountRole()) { throw invalidState(); }
            }).toList();
        } catch (InstitutionAssignmentStorageException exception) {
            throw invalidState();
        }
    }

    private AdminOrgOperationsResponse read(Org org, @Nullable UUID requesterId, @Nullable InstitutionSelectionTarget target) {
        InstitutionMailSetting setting = jdbc.queryForObject("select admin_revision,request_mail_mode from orgs where id=?",
                (rs, index) -> new InstitutionMailSetting(rs.getLong(1), rs.getString(2) == null ? null
                        : RequestMailMode.valueOf(rs.getString(2))), org.getId());
        return evaluate(OrgDetailResponse.from(org), setting.revision(), setting.mode(), rows(org.getId()), requesterId, target);
    }

    AdminOrgOperationsResponse evaluate(OrgDetailResponse org, long revision, @Nullable RequestMailMode mode,
            List<InstitutionStaffRow> rows, @Nullable UUID requesterId, @Nullable InstitutionSelectionTarget target) {
        Set<UUID> liveWorkspaceIds = new HashSet<>(jdbc.queryForList(
                "select public_id from workspaces where deleted_at is null", UUID.class));
        boolean fallback = mode == null && rows.stream().noneMatch(row -> eligible(row, requesterId, target, liveWorkspaceIds) && row.requestMail());
        List<AdminOrgOperationsResponse.AdminOrgRoleMemberView> members = rows.stream().map(row -> {
            boolean eligible = eligible(row, requesterId, target, liveWorkspaceIds);
            boolean selected = eligible && (mode == RequestMailMode.ALL_APPROVERS
                    || (fallback ? row.assignment().mode() == InstitutionAssignment.Mode.FULL : row.requestMail()));
            return new AdminOrgOperationsResponse.AdminOrgRoleMemberView(row.userId(), row.name(), row.email(),
                    row.assignment().displayRole(), InstitutionPermissionView.from(row.assignment()), row.status(),
                    row.requestMail(), selected, eligible, selected ? fallback ? "LEGACY_ORG_ADMIN"
                            : mode == RequestMailMode.ALL_APPROVERS ? "ALL_APPROVERS" : "DESIGNATED" : null,
                    selected ? null : exclusionReason(row, requesterId, eligible, target));
        }).toList();
        int full = (int) rows.stream().filter(row -> row.status() == UserStatus.ACTIVE
                && row.assignment().mode() == InstitutionAssignment.Mode.FULL).count();
        int deciders = (int) rows.stream().filter(row -> eligible(row, null, null, liveWorkspaceIds)).count();
        int recipients = (int) members.stream().filter(member -> Boolean.TRUE.equals(member.currentMailRecipient())).count();
        var context=target==null ? null : new AdminOrgOperationsResponse.RequestMailSelectionContext(target.type(),target.target().workspaceId());
        var references=jdbc.query("""
                select distinct q.resource_type::text,w.public_id,w.name from requests q
                  join workspaces w on w.id=q.workspace_id join orgs o on o.id=q.org_id
                 where o.public_id=? and w.deleted_at is null order by q.resource_type::text,w.name,w.public_id
                """,(rs,index)->new AdminOrgOperationsResponse.RequestMailSelectionTarget(ResourceType.valueOf(rs.getString(1)),
                rs.getObject(2,UUID.class),rs.getString(3)),org.id());
        return new AdminOrgOperationsResponse(org,revision,mode,members,full,deciders,recipients,fallback,requesterId,Instant.now(),context,references);
    }

    boolean mayDecide(InstitutionAssignment assignment) {
        Set<UUID> live = new HashSet<>(jdbc.queryForList("select public_id from workspaces where deleted_at is null", UUID.class));
        return hasAnyDecision(assignment, live);
    }

    /** Status and withdrawal checks use the same complete assignments as roster editing. */
    public InstitutionStaffing staffing(long orgId, @Nullable UUID excludedAccount) {
        return staffing(rows(orgId).stream().filter(row -> !row.userId().equals(excludedAccount)).toList());
    }

    InstitutionStaffing staffing(List<InstitutionStaffRow> rows) {
        Set<UUID> live = new HashSet<>(jdbc.queryForList("select public_id from workspaces where deleted_at is null", UUID.class));
        var active = rows.stream().filter(row -> row.status() == UserStatus.ACTIVE).toList();
        var byKind = new java.util.EnumMap<ResourceType, Integer>(ResourceType.class);
        for (ResourceType type : ResourceType.values()) {
            InstitutionWork work = InstitutionAuthorization.requestDecisionWork(type).orElseThrow();
            byKind.put(type, (int) active.stream().filter(row -> hasDecision(row.assignment(), work, live)).count());
        }
        return new InstitutionStaffing((int) active.stream().filter(row -> row.assignment().mode() == InstitutionAssignment.Mode.FULL).count(),
                (int) active.stream().filter(row -> hasAnyDecision(row.assignment(), live)).count(), java.util.Map.copyOf(byKind));
    }

    public List<UUID> activeFullAccounts(long orgId) {
        return rows(orgId).stream().filter(row -> row.status() == UserStatus.ACTIVE
                && row.assignment().mode() == InstitutionAssignment.Mode.FULL).map(InstitutionStaffRow::userId).toList();
    }

    public InstitutionStaffing staffingWithStatus(long orgId, UUID account, UserStatus status) {
        return staffing(rows(orgId).stream().map(row -> row.userId().equals(account)
                ? new InstitutionStaffRow(row.userId(), row.name(), row.email(), status, row.assignment(), row.requestMail(), row.accountRole()) : row).toList());
    }

    private static boolean eligible(InstitutionStaffRow row, @Nullable UUID requesterId,
            @Nullable InstitutionSelectionTarget target, Set<UUID> live) {
        if (row.status() != UserStatus.ACTIVE || row.userId().equals(requesterId)) { return false; }
        if (target == null) { return hasAnyDecision(row.assignment(), live); }
        return InstitutionAuthorization.requestDecisionWork(target.type()).map(work ->
                new InstitutionAuthority(row.userId(), List.of(row.assignment())).evaluate(work, target.target()).allowed()).orElse(false);
    }

    private static boolean hasAnyDecision(InstitutionAssignment assignment, Set<UUID> live) {
        return List.of(InstitutionWork.VM_REQUEST_DECIDE, InstitutionWork.LLM_REQUEST_DECIDE,
                InstitutionWork.DOMAIN_REQUEST_DECIDE, InstitutionWork.GPU_REQUEST_DECIDE).stream().anyMatch(work -> hasDecision(assignment, work, live));
    }

    private static boolean hasDecision(InstitutionAssignment assignment, InstitutionWork work, Set<UUID> live) {
        if (!assignment.includes(work)) { return false; }
        if (assignment.mode() == InstitutionAssignment.Mode.FULL) { return true; }
        InstitutionWorkspaceScope scope = assignment.scopes().get(work.group());
        return scope.mode() == InstitutionWorkspaceScope.Mode.ALL || scope.workspaceIds().stream().anyMatch(live::contains);
    }

    private static String exclusionReason(InstitutionStaffRow row, @Nullable UUID requesterId, boolean eligible, @Nullable InstitutionSelectionTarget target) {
        if (row.userId().equals(requesterId)) { return "REQUESTER_EXCLUDED"; }
        if (row.status() != UserStatus.ACTIVE) { return "ACCOUNT_INACTIVE"; }
        if (!eligible && target!=null) {
            var work=InstitutionAuthorization.requestDecisionWork(target.type()).orElseThrow();
            return row.assignment().includes(work) ? "TARGET_SCOPE_EXCLUDED" : "DECISION_WORK_MISSING";
        }
        return eligible ? "NOT_SELECTED" : "PERMISSION_CANNOT_RECEIVE";
    }

    private boolean has(AuthenticatedUser actor, InstitutionWork work, long orgId) {
        return authorization.institutionTarget(orgId).map(target -> authorization.decision(actor, work, target).allowed()).orElse(false);
    }

    private static AdminOrgOperationsResponse project(AdminOrgOperationsResponse value, boolean staff, boolean mail) {
        return new AdminOrgOperationsResponse(value.org(), value.revision(), mail ? value.mailMode() : null,
                value.members().stream().map(member -> new AdminOrgOperationsResponse.AdminOrgRoleMemberView(member.userId(),
                        member.name(), member.email(), member.role(), staff ? member.permission() : null, member.status(),
                        mail ? member.requestMail() : null, mail ? member.currentMailRecipient() : null,
                        mail ? member.inAppRecipient() : null, mail ? member.selectionReason() : null,
                        mail ? member.exclusionReason() : null)).toList(), value.activeAdminCount(), value.activeApproverCount(),
                mail ? value.currentMailRecipientCount() : null, mail ? value.legacyFallback() : null, value.requesterId(), value.observedAt(),
                mail ? value.selectionContext() : null, mail ? value.selectionTargets() : null);
    }

    static ApiException invalidState() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.ORG_OPERATIONS_CONFLICT,
                "기관 권한 상태를 확인해 주세요", "기관 권한이나 표시 역할이 일치하지 않습니다. 자동으로 권한을 복구하지 않습니다.");
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND, "기관을 찾을 수 없습니다", "조회할 수 있는 기관을 찾을 수 없습니다.");
    }

    record InstitutionStaffRow(UUID userId, String name, String email, UserStatus status,
            InstitutionAssignment assignment, boolean requestMail, UserRole accountRole) { }
    record InstitutionSelectionTarget(ResourceType type, InstitutionWorkTarget target) { }
    private record InstitutionMailSetting(long revision, @Nullable RequestMailMode mode) { }
    public record InstitutionStaffing(int fullCount, int deciderCount, java.util.Map<ResourceType, Integer> decidersByKind) {
        public boolean losesLastStaff(InstitutionStaffing after) {
            return fullCount > 0 && after.fullCount == 0 || deciderCount > 0 && after.deciderCount == 0;
        }
    }
}
