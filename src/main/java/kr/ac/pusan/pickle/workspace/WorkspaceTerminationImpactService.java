package kr.ac.pusan.pickle.workspace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceAccessResolver;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.StudentSupportPolicy;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.request.RequestRecipientStatus;
import kr.ac.pusan.pickle.request.RequestStatus;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.dto.VmSummaryResponse;
import kr.ac.pusan.pickle.vmsettings.VmSettingsService;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceTerminationImpactResponse;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceTerminationImpactResponse.Action;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceTerminationImpactResponse.WorkspaceTerminationRecipientCount;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceTerminationImpactResponse.WorkspaceTerminationResourceBlocker;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceTerminationImpactResponse.WorkspaceTerminationSummary;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceTerminationTargetResponse;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceTerminationResourceResponse;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceTerminationTargetResponse.Kind;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read-only snapshots of the same predicates used by automatic termination. */
@Service
public class WorkspaceTerminationImpactService {
    private final LifecycleActors lifecycleActors;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final WorkspaceInvitationRepository invitations;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final ResourceAccessResolver access;
    private final VmRepository vms;
    private final VmSettingsService vmSettings;

    public WorkspaceTerminationImpactService(WorkspaceRepository workspaces, WorkspaceMemberRepository members,
            WorkspaceInvitationRepository invitations, UserRepository users, JdbcTemplate jdbc,
            ResourceAccessResolver access, VmRepository vms, VmSettingsService vmSettings, LifecycleActors lifecycleActors) {
        this.lifecycleActors = lifecycleActors;
        this.workspaces = workspaces; this.members = members; this.invitations = invitations;
        this.users = users; this.jdbc = jdbc;
        this.access = access; this.vms = vms; this.vmSettings = vmSettings;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkspaceTerminationImpactResponse workspace(AuthenticatedUser actor, UUID workspaceId,
            boolean support, int page, int size, int resourcePage) {
        actor = lifecycleActors.current(actor);
        Workspace workspace = requireWorkspace(actor, workspaceId, support);
        return observe(actor, workspace, null, support, page, size, resourcePage);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkspaceTerminationImpactResponse invitation(AuthenticatedUser actor, UUID workspaceId,
            UUID invitationId, boolean support, int page, int size, int resourcePage) {
        actor = lifecycleActors.current(actor);
        Workspace workspace = requireWorkspace(actor, workspaceId, support);
        WorkspaceInvitation invitation = invitations.findByPublicId(invitationId)
                .filter(found -> found.getWorkspaceId().equals(workspace.getId()))
                .filter(found -> found.getStatus() == WorkspaceInvitationStatus.PENDING)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.WORKSPACE_INVITATION_NOT_FOUND,
                        "초대를 찾을 수 없습니다", "대기 중인 초대가 아닙니다. 이미 수락되었거나 취소되었을 수 있습니다."));
        return observe(actor, workspace, invitation, support, page, size, resourcePage);
    }

    public WorkspaceTerminationImpactResponse lockedSnapshot(AuthenticatedUser actor, Workspace workspace,
            @Nullable WorkspaceInvitation invitation, boolean support) {
        return observe(actor, workspace, invitation, support, 0, 1, 0);
    }

    private Workspace requireWorkspace(AuthenticatedUser actor, UUID publicId, boolean support) {
        if (users.findById(actor.id()).map(user -> user.getStatus()).orElse(null) != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_TOKEN_INVALID,
                    "인증이 필요합니다", "현재 활성 계정으로 다시 로그인해 주세요.");
        }
        if (support) StudentSupportPolicy.requireWorkspaceAccessWrite(actor);
        Workspace workspace = workspaces.findByPublicIdAndDeletedAtIsNull(publicId).orElseThrow(WorkspaceTerminationImpactService::notFound);
        if (!support) {
            WorkspaceMember membership = members.findByWorkspaceIdAndUserId(workspace.getId(), actor.id()).orElseThrow(WorkspaceTerminationImpactService::notFound);
            if (membership.getRole() != WorkspaceMemberRole.OWNER) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.WORKSPACE_ROLE_INSUFFICIENT,
                        "종료 영향을 조회할 권한이 없습니다", "워크스페이스의 현재 OWNER만 종료 영향을 조회할 수 있습니다.");
            }
        }
        return workspace;
    }

    private WorkspaceTerminationImpactResponse observe(AuthenticatedUser actor, Workspace workspace,
            @Nullable WorkspaceInvitation invitation, boolean support, int page, int size, int resourcePage) {
        if (page < 0 || resourcePage < 0 || size < 1 || size > 100) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("page", "페이지는 0 이상, 크기는 1에서 100 사이여야 합니다.")));
        }
        boolean deleting = invitation == null;
        String condition = deleting ? "r.workspace_id = ?" : "r.workspace_id = ? and rr.invitation_id = ?";
        Object[] args = deleting ? new Object[]{workspace.getId()} : new Object[]{workspace.getId(), invitation.getId()};
        String recipientCondition = deleting ? "rr.status in ('QUEUED','PENDING_JOIN','FAILED')"
                : "rr.status in ('PENDING_JOIN','FAILED')";
        List<WorkspaceTerminationRecipientCount> counts = jdbc.query("""
                select r.status::text request_status, rr.status recipient_status, count(*) count
                  from requests r join request_recipients rr on rr.request_id = r.id
                 where %s and %s group by r.status, rr.status order by r.status::text, rr.status
                """.formatted(condition, recipientCondition), (rs, index) -> new WorkspaceTerminationRecipientCount(
                        RequestStatus.valueOf(rs.getString("request_status")), RequestRecipientStatus.valueOf(rs.getString("recipient_status")),
                        rs.getLong("count"), !"FAILED".equals(rs.getString("recipient_status"))), args);
        long submitted = deleting ? jdbc.queryForObject("select count(*) from requests where workspace_id = ? and status = 'SUBMITTED'",
                Long.class, workspace.getId()) : 0;
        WorkspaceTerminationSummary summary = new WorkspaceTerminationSummary(submitted, total(counts, null, null, true), total(counts, null, RequestRecipientStatus.FAILED, false),
                total(counts, null, RequestRecipientStatus.PENDING_JOIN, true), total(counts, null, RequestRecipientStatus.QUEUED, true),
                total(counts, RequestStatus.APPROVED, null, true), total(counts, RequestStatus.SUBMITTED, null, true));
        List<WorkspaceTerminationResourceBlocker> blockers = resourceCounts(workspace, deleting);
        long blockingTotal = blockers.stream().mapToLong(WorkspaceTerminationResourceBlocker::count).sum();
        Map<ResourceType, Map<Long, ResourceRole>> resourceRoles = resourceRoles(actor, workspace, deleting);
        List<BlockingResource> resourceRows = deleting ? jdbc.query(RESOURCE_QUERY
                + " select type,id,public_id,name,org_id,org_name,state from blocking_resources order by kind_sort,id limit ? offset ?",
                (rs, index) -> new BlockingResource(ResourceType.valueOf(rs.getString("type")), rs.getLong("id"), rs.getObject("public_id", UUID.class),
                        rs.getString("name"), rs.getObject("org_id", UUID.class), rs.getString("org_name"), rs.getString("state")),
                workspace.getId(), workspace.getId(), workspace.getId(), workspace.getId(), size, (long) resourcePage * size) : List.of();
        List<WorkspaceTerminationResourceResponse> blockingRows = resourceProjection(workspace, support, resourceRows, resourceRoles);
        PageResponse<WorkspaceTerminationResourceResponse> blockingResources = new PageResponse<>(blockingRows, resourcePage, size,
                blockingTotal, (int) ((blockingTotal + size - 1) / size));
        String blocked = workspace.getKind() == WorkspaceKind.PERSONAL ? "PERSONAL"
                : blockers.stream().anyMatch(blocker -> blocker.count() > 0) ? "REMAINING_RESOURCES" : null;
        String cte = targetQuery(deleting, condition, recipientCondition);
        List<Object> targetArgs = new ArrayList<>();
        if (deleting) targetArgs.add(workspace.getId());
        targetArgs.addAll(List.of(args));
        long total = submitted + counts.stream().mapToLong(WorkspaceTerminationRecipientCount::count).sum();
        List<Object> pageArgs = new ArrayList<>(targetArgs);
        pageArgs.add(size); pageArgs.add((long) page * size);
        List<WorkspaceTerminationTargetResponse> targets = jdbc.query(cte + " select * from targets order by request_sort,kind_sort,recipient_sort limit ? offset ?",
                (rs, index) -> new WorkspaceTerminationTargetResponse(Kind.valueOf(rs.getString("kind")), rs.getObject("request_id", UUID.class),
                        rs.getString("request_name"), RequestStatus.valueOf(rs.getString("request_status")), ResourceType.valueOf(rs.getString("type")),
                        rs.getObject("org_id", UUID.class), rs.getString("org_name"), true, rs.getObject("recipient_id", UUID.class),
                        rs.getObject("invitation_id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("name"), rs.getString("invitee"),
                        support && rs.getString("user_role") != null && StudentSupportPolicy.canReadUser(actor, UserRole.valueOf(rs.getString("user_role"))),
                        rs.getString("recipient_status") == null ? null : RequestRecipientStatus.valueOf(rs.getString("recipient_status")),
                        rs.getString("reason"), rs.getObject("resource_id", UUID.class), rs.getBoolean("will_close")), pageArgs.toArray());
        String fingerprint = fingerprint(actor, workspace, invitation, support, cte, targetArgs, blockers, resourceRoles);
        return new WorkspaceTerminationImpactResponse(deleting ? Action.DELETE_WORKSPACE : Action.CANCEL_INVITATION,
                workspace.getPublicId(), workspace.getName(), invitation == null ? null : invitation.getPublicId(), Instant.now(), fingerprint,
                blocked == null, blocked, summary, counts, blockers, blockingResources, new PageResponse<>(targets, page, size, total, (int) ((total + size - 1) / size)));
    }

    private String fingerprint(AuthenticatedUser actor, Workspace workspace, @Nullable WorkspaceInvitation invitation,
            boolean support, String cte, List<Object> args, List<WorkspaceTerminationResourceBlocker> blockers,
            Map<ResourceType, Map<Long, ResourceRole>> resourceRoles) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, actor.publicId() + ":" + actor.role() + ":" + support + ":" + workspace.getPublicId() + ":" + workspace.getKind()
                    + ":" + members.findByWorkspaceIdAndUserId(workspace.getId(), actor.id()).map(WorkspaceMember::getRole).orElse(null));
            actor.orgRoles().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> update(digest, entry.toString()));
            update(digest, invitation == null ? "DELETE_WORKSPACE" : "CANCEL_INVITATION:" + invitation.getPublicId() + ":" + invitation.getStatus());
            jdbc.query(cte + " select kind,request_id,request_status,type,org_id,recipient_id,invitation_id,user_id,recipient_status,resource_binding,will_close from targets order by request_sort,kind_sort,recipient_sort",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) update(digest, String.valueOf(rs.getObject(i)));
                    }, args.toArray());
            for (WorkspaceTerminationResourceBlocker blocker : blockers) update(digest, blocker.toString());
            if (invitation == null) {
                // Include exact resource states, even when totals remain unchanged.
                jdbc.query(RESOURCE_QUERY + " select type,id,state,org_id from blocking_resources order by kind_sort,id",
                        (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) update(digest, String.valueOf(rs.getObject(i)));
                            ResourceType type = ResourceType.valueOf(rs.getString("type"));
                            ResourceRole role = resourceRoles.getOrDefault(type, Map.of()).get(rs.getLong("id"));
                            update(digest, String.valueOf(role));
                            update(digest, String.valueOf(resourceReadable(support, type, rs.getObject("org_id", UUID.class), role)));
                        }, workspace.getId(), workspace.getId(), workspace.getId(), workspace.getId());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private Map<ResourceType, Map<Long, ResourceRole>> resourceRoles(AuthenticatedUser actor, Workspace workspace, boolean deleting) {
        Map<ResourceType, Map<Long, ResourceRole>> roles = new java.util.EnumMap<>(ResourceType.class);
        // listAccess roles are valid only for a member of the owning workspace.
        if (!deleting || members.findByWorkspaceIdAndUserId(workspace.getId(), actor.id()).isEmpty()) return roles;
        Map<ResourceType, List<Long>> ids = new java.util.EnumMap<>(ResourceType.class);
        jdbc.query(RESOURCE_QUERY + " select type,id from blocking_resources order by kind_sort,id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> ids.computeIfAbsent(ResourceType.valueOf(rs.getString("type")), ignored -> new ArrayList<>()).add(rs.getLong("id")),
                workspace.getId(), workspace.getId(), workspace.getId(), workspace.getId());
        ids.forEach((type, listed) -> roles.put(type, access.listAccess(type, listed, actor.id()).roles()));
        return roles;
    }

    private List<WorkspaceTerminationResourceResponse> resourceProjection(Workspace workspace, boolean support,
            List<BlockingResource> resources, Map<ResourceType, Map<Long, ResourceRole>> roles) {
        List<Long> restrictedVmIds = resources.stream().filter(row -> row.type() == ResourceType.VM
                && !resourceReadable(support, row.type(), row.orgId(), roles.getOrDefault(row.type(), Map.of()).get(row.id())))
                .map(BlockingResource::id).toList();
        Map<Long, Vm> restrictedVms = vms.findAllById(restrictedVmIds).stream()
                .collect(java.util.stream.Collectors.toMap(Vm::getId, java.util.function.Function.identity()));
        Map<Long, String> displayNames = vmSettings.displayNames(restrictedVmIds);
        return resources.stream().map(row -> {
            boolean readable = resourceReadable(support, row.type(), row.orgId(), roles.getOrDefault(row.type(), Map.of()).get(row.id()));
            String name = row.name();
            if (!readable && row.type() == ResourceType.VM) {
                Vm vm = restrictedVms.get(row.id());
                if (vm == null) throw new IllegalStateException("A blocking VM disappeared inside a repeatable-read snapshot");
                name = VmSummaryResponse.restricted(vm, workspace.getPublicId(), workspace.getName(), displayNames.get(vm.getId()), List.of(), true).name();
            }
            return new WorkspaceTerminationResourceResponse(row.type(), row.publicId(), name,
                    !support && !readable ? null : row.orgId(), !support && !readable ? null : row.orgName(), row.state(), readable);
        }).toList();
    }

    private static boolean resourceReadable(boolean support, ResourceType type, @Nullable UUID orgId, @Nullable ResourceRole role) {
        return support ? type != ResourceType.DOMAIN || orgId != null : role != null;
    }

    private record BlockingResource(ResourceType type, long id, UUID publicId, String name,
            @Nullable UUID orgId, @Nullable String orgName, String state) { }

    private List<WorkspaceTerminationResourceBlocker> resourceCounts(Workspace workspace, boolean deleting) {
        if (!deleting) return List.of();
        Map<ResourceType, Long> counts = new java.util.EnumMap<>(ResourceType.class);
        jdbc.query(RESOURCE_QUERY + " select type,count(*) from blocking_resources group by type",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> counts.put(ResourceType.valueOf(rs.getString(1)), rs.getLong(2)),
                workspace.getId(), workspace.getId(), workspace.getId(), workspace.getId());
        return java.util.Arrays.stream(ResourceType.values()).map(type -> new WorkspaceTerminationResourceBlocker(type, counts.getOrDefault(type, 0L))).toList();
    }

    // The predicates match each resource adapter's countLiveInWorkspace contract.
    private static final String RESOURCE_QUERY = """
            with blocking_resources as (
              select 'VM' type,0 kind_sort,v.id,v.public_id,v.name,v.org_id internal_org,o.public_id org_id,o.name org_name,v.status::text state
                from vms v left join orgs o on o.id=v.org_id where v.workspace_id=? and v.deleted_at is null and v.status<>'DELETED'
              union all
              select 'LLM_API_KEY',1,k.id,k.public_id,k.name,k.org_id,o.public_id,o.name,k.status::text
                from llm_api_keys k left join orgs o on o.id=k.org_id where k.workspace_id=? and k.status<>'REVOKED'
              union all
              select 'DOMAIN',2,d.id,d.public_id,d.fqdn,d.org_id,o.public_id,o.name,d.status::text
                from domains d left join orgs o on o.id=d.org_id where d.workspace_id=? and d.kind='EXTERNAL' and d.status<>'REMOVED'
              union all
              select 'GPU',3,g.id,g.public_id,g.name,g.org_id,o.public_id,o.name,g.status::text
                from gpu_allocations g left join orgs o on o.id=g.org_id where g.workspace_id=? and g.status not in ('RELEASED','CANCELED')
            )
            """;

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }

    private static long total(List<WorkspaceTerminationRecipientCount> counts, @Nullable RequestStatus request, @Nullable RequestRecipientStatus recipient, boolean closing) {
        return counts.stream().filter(count -> count.willClose() == closing && (request == null || count.requestStatus() == request)
                && (recipient == null || count.recipientStatus() == recipient)).mapToLong(WorkspaceTerminationRecipientCount::count).sum();
    }

    private static String targetQuery(boolean deleting, String condition, String recipientCondition) {
        String requestRows = deleting ? """
                select 'REQUEST' kind,r.id request_sort,0 kind_sort,0::bigint recipient_sort,r.public_id request_id,r.display_name request_name,
                       r.status::text request_status,r.resource_type::text type,o.public_id org_id,o.name org_name,
                       null::uuid recipient_id,null::uuid invitation_id,null::uuid user_id,null::text name,null::text invitee,null::text user_role,
                       null::text recipient_status,null::text reason,null::uuid resource_id,null::bigint resource_binding,true will_close
                  from requests r join orgs o on o.id=r.org_id where r.workspace_id=? and r.status='SUBMITTED'
                union all
                """ : "";
        return "with targets as (" + requestRows + """
                select 'RECIPIENT' kind,r.id request_sort,1 kind_sort,rr.id recipient_sort,r.public_id request_id,r.display_name request_name,
                       r.status::text request_status,r.resource_type::text type,o.public_id org_id,o.name org_name,
                       rr.public_id recipient_id,i.public_id invitation_id,u.public_id user_id,u.name,coalesce(i.invitee_email::text,i.invitee_student_no) invitee,u.role::text user_role,
                       rr.status recipient_status,rr.reason,coalesce(v.public_id,k.public_id) resource_id,rr.resource_id resource_binding,rr.status<>'FAILED' will_close
                  from requests r join orgs o on o.id=r.org_id join request_recipients rr on rr.request_id=r.id
                  left join users u on u.id=rr.user_id left join workspace_invitations i on i.id=rr.invitation_id
                  left join vms v on r.resource_type='VM' and v.id=rr.resource_id
                  left join llm_api_keys k on r.resource_type='LLM_API_KEY' and k.id=rr.resource_id
                 where %s and %s)
                """.formatted(condition, recipientCondition);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND, "리소스를 찾을 수 없습니다", "해당 워크스페이스가 존재하지 않습니다.");
    }
}
