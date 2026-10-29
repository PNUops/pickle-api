package kr.ac.pusan.pickle.request;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.security.institution.InstitutionAuthorization;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.orgs.OrgAdministrationLock;
import kr.ac.pusan.pickle.request.dto.CancelRequestRecipientRequest;
import kr.ac.pusan.pickle.request.dto.RequestRecipientCancellationCapability;
import kr.ac.pusan.pickle.request.dto.RequestRecipientCancellationImpactResponse;
import kr.ac.pusan.pickle.request.dto.RequestRecipientCancellationReceipt;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.workspace.LifecycleActors;
import kr.ac.pusan.pickle.workspace.TerminationConfirmations;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A bounded pre-registration cancellation; registered resources have separate lifecycle paths. */
@Service
public class RequestRecipientCancellationService {
    private final InstitutionAuthorization institutionAuthorization;
    private final OrgAdministrationLock administrationLock;
    private final LifecycleActors actors;
    private final RequestRepository requests;
    private final RequestRecipientRepository recipients;
    private final WorkspaceRepository workspaces;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper json;
    private final jakarta.persistence.EntityManager entities;

    public RequestRecipientCancellationService(OrgAdministrationLock administrationLock, LifecycleActors actors,
            RequestRepository requests, RequestRecipientRepository recipients, WorkspaceRepository workspaces,
            JdbcTemplate jdbc, AuditService audit, ObjectMapper json, jakarta.persistence.EntityManager entities, InstitutionAuthorization institutionAuthorization) {
        this.institutionAuthorization = institutionAuthorization;
        this.administrationLock = administrationLock; this.actors = actors; this.requests = requests;
        this.recipients = recipients; this.workspaces = workspaces; this.jdbc = jdbc;
        this.audit = audit; this.json = json; this.entities = entities;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RequestRecipientCancellationImpactResponse preview(AuthenticatedUser principal, UUID requestId,
            UUID recipientId, boolean support) {
        AuthenticatedUser actor = actors.current(principal);
        Request request = requests.findByPublicId(requestId).orElseThrow(RequestRecipientCancellationService::notFound);
        Workspace workspace = workspaces.findById(request.getWorkspaceId()).orElseThrow(RequestRecipientCancellationService::notFound);
        requireAuthority(actor, workspace, support);
        RequestRecipient recipient = recipients.findByPublicIdAndRequestId(recipientId, request.getId())
                .orElseThrow(RequestRecipientCancellationService::notFound);
        return impact(actor, request, workspace, recipient, support);
    }

    @Transactional
    public RequestRecipientCancellationReceipt cancel(AuthenticatedUser principal, UUID requestId, UUID recipientId,
            CancelRequestRecipientRequest input, boolean support, String ip) {
        administrationLock.acquire();
        AuthenticatedUser actor = actors.currentAfterAdministrationLock(principal);
        Request request = requests.findByPublicId(requestId).orElseThrow(RequestRecipientCancellationService::notFound);
        // APPROVED decisions are immutable. Do not take a request lock after the workspace.
        Workspace workspace = workspaces.findByIdForUpdate(request.getWorkspaceId()).orElseThrow(RequestRecipientCancellationService::notFound);
        entities.refresh(workspace);
        requireAuthority(actor, workspace, support);
        RequestRecipient found = recipients.findByPublicIdAndRequestId(recipientId, request.getId())
                .orElseThrow(RequestRecipientCancellationService::notFound);
        RequestRecipient recipient = recipients.findWithLockById(found.getId()).orElseThrow(RequestRecipientCancellationService::notFound);
        entities.refresh(recipient);
        String reason = input.reason() == null ? "" : input.reason().strip();
        if (reason.isBlank() || reason.length() > 500) {
            throw ApiException.validationFailed(List.of(new kr.ac.pusan.pickle.common.error.FieldValidationError("reason", "취소 사유는 1자에서 500자 사이여야 합니다.")));
        }
        List<StoredReceipt> saved = storedReceipts(List.of(recipient));
        if (!saved.isEmpty()) {
            StoredReceipt previous = saved.getFirst();
            if (recipient.getStatus() == RequestRecipientStatus.CANCELED && previous.actorId().equals(actor.id())
                    && previous.support() == support && previous.receipt().reason().equals(reason)
                    && input.confirmation() != null && input.confirmation().confirmed()
                    && previous.fingerprint().equals(input.confirmation().fingerprint())) return previous.receipt();
            throw conflict("이미 다른 내용으로 취소된 대상자입니다. 신청 상세에서 기존 결과를 확인해 주세요.");
        }
        var impact = impact(actor, request, workspace, recipient, support);
        TerminationConfirmations.requireConfirmed(impact.fingerprint(), input.confirmation());
        if (!impact.allowed()) throw conflict(impact.blockedReason());
        RequestRecipientCancellationReceipt receipt = new RequestRecipientCancellationReceipt(UUID.randomUUID(),
                request.getPublicId(), workspace.getPublicId(), recipient.getPublicId(), Instant.now(), reason,
                recipient.getStatus(), recipient.getReason(), recipient.getAttempts());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("receiptId", receipt.id()); detail.put("requestId", receipt.requestId());
        detail.put("workspaceId", receipt.workspaceId()); detail.put("recipientId", receipt.recipientId());
        detail.put("canceledAt", receipt.canceledAt().toString()); detail.put("reason", reason);
        detail.put("previousStatus", receipt.previousStatus().name()); detail.put("previousReason", receipt.previousReason());
        detail.put("previousAttempts", receipt.previousAttempts()); detail.put("status", RequestRecipientStatus.CANCELED.name());
        detail.put("confirmationFingerprint", impact.fingerprint()); detail.put("adminIntervention", support);
        recipient.cancelCreation();
        // MANDATORY joins this business transaction. An audit failure rolls back the status too.
        audit.recordInTransaction(actor.id(), actor.role().name(), AuditService.REQUEST_RECIPIENT_CANCEL,
                "request_recipient", recipient.getPublicId(), detail, ip);
        return receipt;
    }

    private RequestRecipientCancellationImpactResponse impact(AuthenticatedUser actor, Request request,
            Workspace workspace, RequestRecipient recipient, boolean support) {
        List<RequestRecipient> rows = recipients.findByRequestIdOrderByIdAsc(request.getId());
        Registration facts = registrations(List.of(request), rows).getOrDefault(request.getId(), Registration.EMPTY);
        String blocked = blocked(request, workspace.getDeletedAt() != null, recipient, facts);
        String binding = institutionAuthorization.authority(actor).map(value -> value.fingerprint()).orElse("invalid") + ":" + actor.publicId() + ":" + actor.role() + ":" + actor.orgRoles().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList() + ":" + support + ":" + owner(actor.id(), workspace.getId())
                + ":" + workspace.getPublicId() + ":" + workspace.getKind() + ":" + workspace.getDeletedAt()
                + ":" + request.getPublicId() + ":" + request.getStatus() + ":" + request.getResourceType() + ":" + request.getOrgId()
                + ":" + recipient.getPublicId() + ":" + recipient.getUserId() + ":" + recipient.getInvitationId()
                + ":" + recipient.getStatus() + ":" + recipient.getResourceId() + ":" + recipient.getReason()
                + ":" + recipient.getAttempts() + ":" + facts.binding()
                + ":" + rows.stream().filter(row -> row.getResourceId() != null)
                        .map(row -> row.getPublicId() + ":" + row.getResourceId()).sorted().toList();
        return new RequestRecipientCancellationImpactResponse(request.getPublicId(), workspace.getPublicId(), recipient.getPublicId(),
                request.getResourceType(), recipient.getStatus(), recipient.getReason(), recipient.getAttempts(),
                blocked == null, blocked, hash(binding), Instant.now());
    }

    public Map<UUID, RequestRecipientCancellationCapability> capabilities(List<Request> requestRows,
            List<RequestRecipient> rows, @Nullable AuthenticatedUser actor) {
        Map<Long, Request> byId = requestRows.stream().collect(Collectors.toMap(Request::getId, row -> row));
        Map<Long, Registration> registrations = registrations(requestRows, rows);
        Map<Long, Boolean> live = new HashMap<>();
        Set<Long> owned = new java.util.HashSet<>();
        if (!requestRows.isEmpty()) jdbc.query("select w.id,w.deleted_at,wm.role from workspaces w left join workspace_members wm on wm.workspace_id=w.id and wm.user_id=? where w.id in ("
                        + placeholders(requestRows.size()) + ")",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    live.put(rs.getLong(1), rs.getTimestamp(2) == null);
                    if ("OWNER".equals(rs.getString(3))) owned.add(rs.getLong(1));
                }, java.util.stream.Stream.concat(java.util.stream.Stream.of(actor == null ? -1L : actor.id()), requestRows.stream().map(Request::getWorkspaceId)).toArray());
        Map<UUID, RequestRecipientCancellationCapability> out = new HashMap<>();
        for (RequestRecipient row : rows) {
            Request request = byId.get(row.getRequestId());
            String blocked = blocked(request, !live.getOrDefault(request.getWorkspaceId(), false), row,
                    registrations.getOrDefault(request.getId(), Registration.EMPTY));
            boolean owner = actor != null && owned.contains(request.getWorkspaceId());
            boolean support = actor != null && supportAuthority(actor, request.getWorkspaceId());
            out.put(row.getPublicId(), new RequestRecipientCancellationCapability(blocked == null && owner,
                    blocked == null && support, blocked != null ? blocked : !owner && !support ? "현재 OWNER 또는 지원 쓰기 권한이 필요합니다." : null));
        }
        return out;
    }

    public Map<UUID, RequestRecipientCancellationReceipt> receipts(List<RequestRecipient> rows) {
        Map<UUID, RequestRecipientCancellationReceipt> result = new HashMap<>();
        storedReceipts(rows).forEach(saved -> result.put(saved.receipt().recipientId(), saved.receipt()));
        return result;
    }

    private List<StoredReceipt> storedReceipts(List<RequestRecipient> rows) {
        if (rows.isEmpty()) return List.of();
        return jdbc.query("select actor_id,detail::text from audit_logs where action=? and target_type='request_recipient' and target_id in ("
                        + placeholders(rows.size()) + ") order by id", (rs, index) -> {
                    JsonNode data = json.readTree(rs.getString(2));
                    var receipt = new RequestRecipientCancellationReceipt(UUID.fromString(data.get("receiptId").asString()),
                            UUID.fromString(data.get("requestId").asString()), UUID.fromString(data.get("workspaceId").asString()),
                            UUID.fromString(data.get("recipientId").asString()), Instant.parse(data.get("canceledAt").asString()),
                            data.get("reason").asString(), RequestRecipientStatus.valueOf(data.get("previousStatus").asString()),
                            data.get("previousReason").isNull() ? null : data.get("previousReason").asString(), data.get("previousAttempts").asInt());
                    return new StoredReceipt(rs.getLong(1), data.get("adminIntervention").asBoolean(),
                            data.get("confirmationFingerprint").asString(), receipt);
                }, java.util.stream.Stream.concat(java.util.stream.Stream.of(AuditService.REQUEST_RECIPIENT_CANCEL), rows.stream().map(row -> row.getPublicId().toString())).toArray());
    }

    private record StoredReceipt(Long actorId, boolean support, String fingerprint, RequestRecipientCancellationReceipt receipt) { }
    private record Registration(boolean ambiguous, String binding) {
        private static final Registration EMPTY = new Registration(false, "");
    }

    private Map<Long, Registration> registrations(List<Request> requests, List<RequestRecipient> rows) {
        Map<Long, Registration> result = new HashMap<>();
        Map<Long, Map<Long, Long>> linked = new HashMap<>();
        rows.stream().filter(row -> row.getResourceId() != null && row.getStatus() == RequestRecipientStatus.CREATED)
                .forEach(row -> linked.computeIfAbsent(row.getRequestId(), ignored -> new HashMap<>())
                        .merge(row.getResourceId(), 1L, Long::sum));
        for (ResourceType type : List.of(ResourceType.VM, ResourceType.LLM_API_KEY)) {
            List<Long> ids = requests.stream().filter(request -> request.getResourceType() == type).map(Request::getId).toList();
            if (ids.isEmpty()) continue;
            String table = type == ResourceType.VM ? "vms" : "llm_api_keys";
            jdbc.query("select request_id,id,public_id,status::text from " + table + " where request_id in (" + placeholders(ids.size()) + ") order by request_id,id",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        long request = rs.getLong(1);
                        Registration before = result.getOrDefault(request, Registration.EMPTY);
                        boolean unlinked = linked.getOrDefault(request, Map.of()).getOrDefault(rs.getLong(2), 0L) != 1L;
                        result.put(request, new Registration(before.ambiguous() || unlinked,
                                before.binding() + ":" + rs.getLong(2) + ":" + rs.getString(3) + ":" + rs.getString(4) + ":" + unlinked));
                    }, ids.toArray());
        }
        return result;
    }

    private static @Nullable String blocked(Request request, boolean deleted, RequestRecipient recipient, Registration registration) {
        if (deleted) return "삭제된 워크스페이스의 대상자는 취소할 수 없습니다.";
        if (request.getStatus() != RequestStatus.APPROVED || request.getResourceType() != ResourceType.VM && request.getResourceType() != ResourceType.LLM_API_KEY)
            return "승인된 VM 또는 LLM 키 명단의 대상자만 취소할 수 있습니다.";
        if (recipient.getResourceId() != null) return "자원 연결이 있는 대상자입니다. 기존 자원 상세에서 삭제 또는 폐기 경로를 확인해 주세요.";
        if (registration.ambiguous()) return "신청에 귀속을 확인할 수 없는 등록 자원이 있습니다. 기존 자원 상세에서 연결과 삭제 또는 폐기 경로를 확인해 주세요.";
        if (!List.of(RequestRecipientStatus.PENDING_JOIN, RequestRecipientStatus.QUEUED, RequestRecipientStatus.FAILED).contains(recipient.getStatus()))
            return "가입 또는 생성 대기와 자원 등록 전 실패 대상자만 취소할 수 있습니다.";
        return null;
    }

    private void requireAuthority(AuthenticatedUser actor, Workspace workspace, boolean support) {
        if (support) {
            if (!supportAuthority(actor, workspace.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED, "접근 권한이 없습니다", "이 워크스페이스의 대상자 취소 권한이 없습니다.");
            }
        } else if (!owner(actor.id(), workspace.getId())) throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.WORKSPACE_ROLE_INSUFFICIENT,
                "생성 대기를 취소할 권한이 없습니다", "워크스페이스의 현재 실제 OWNER만 생성 대기를 취소할 수 있습니다.");
    }
    private boolean supportAuthority(AuthenticatedUser actor, long workspaceId) {
        if (actor.role() == UserRole.SYS_ADMIN) { return true; }
        return institutionAuthorization.workspaceTarget(workspaceId)
                .map(target -> institutionAuthorization.decision(actor, InstitutionWork.WORKSPACE_RECIPIENT_CANCEL, target).allowed()).orElse(false);
    }
    private boolean owner(long actorId, long workspaceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from workspace_members where workspace_id=? and user_id=? and role='OWNER')", Boolean.class, workspaceId, actorId));
    }
    private static String placeholders(int count) { return java.util.Collections.nCopies(count, "?").stream().collect(Collectors.joining(",")); }
    private static String hash(String binding) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binding.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ApiException conflict(@Nullable String reason) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.REQUEST_RECIPIENT_NOT_CANCELABLE, "생성 대기를 취소할 수 없습니다", reason);
    }
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND, "대상자를 찾을 수 없습니다", "해당 신청 대상자가 존재하지 않습니다.");
    }
}
