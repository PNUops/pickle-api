package kr.ac.pusan.pickle.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.ResourceAccessGrantRepository;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.ApproveRequestRequest;
import kr.ac.pusan.pickle.admin.dto.RejectRequestRequest;
import kr.ac.pusan.pickle.audit.AuditIds;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.orgs.AdminOrgScope;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.request.RequestAssembler;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.request.RequestRepository;
import kr.ac.pusan.pickle.request.RequestSpecs;
import kr.ac.pusan.pickle.request.RequestReview;
import kr.ac.pusan.pickle.request.RequestTypeHandler;
import kr.ac.pusan.pickle.request.RequestReviewRepository;
import kr.ac.pusan.pickle.request.RequestStatus;
import kr.ac.pusan.pickle.request.dto.RequestDetailResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin approval flow (contract tag {@code admin}, vm-requests subset). The org
 * tier reads the requests of every organisation it holds a role in and decides
 * only in those it operates; anything outside answers 404 (existence stays
 * private, per contract), which is why the read lookup and the write lookup are
 * separate methods rather than one condition. Approval is a single
 * transaction that writes intent only: review row + CREATING vm row + a
 * JobRunr provisioning job; no Proxmox call happens here.
 */
@Service
public class ApprovalService {

    private final RequestRepository requestRepository;
    private final RequestReviewRepository reviewRepository;
    private final kr.ac.pusan.pickle.request.RequestApproval requestApproval;
    private final RequestAssembler assembler;
    private final Map<ResourceType, RequestTypeHandler> handlers;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ResourceAccessGrantRepository grantRepository;
    private final UserRepository userRepository;
    private final OrgRepository orgRepository;
    private final AuditService auditService;
    private final AuditIds auditIds;
    private final NotificationService notificationService;
    private final kr.ac.pusan.pickle.request.RequestRecipientService recipientService;

    public ApprovalService(RequestRepository requestRepository, RequestReviewRepository reviewRepository,
            kr.ac.pusan.pickle.request.RequestApproval requestApproval,
            RequestAssembler assembler, List<RequestTypeHandler> handlers,
            WorkspaceMemberRepository workspaceMemberRepository,
            WorkspaceRepository workspaceRepository,
            ResourceAccessGrantRepository grantRepository, UserRepository userRepository,
            OrgRepository orgRepository,
            AuditService auditService, AuditIds auditIds, NotificationService notificationService,
            kr.ac.pusan.pickle.request.RequestRecipientService recipientService) {
        this.recipientService = recipientService;
        this.requestRepository = requestRepository;
        this.reviewRepository = reviewRepository;
        this.requestApproval = requestApproval;
        this.assembler = assembler;
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(RequestTypeHandler::type, Function.identity()));
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.workspaceRepository = workspaceRepository;
        this.grantRepository = grantRepository;
        this.userRepository = userRepository;
        this.orgRepository = orgRepository;
        this.auditService = auditService;
        this.auditIds = auditIds;
        this.notificationService = notificationService;
    }

    @Transactional(readOnly = true)
    public PageResponse<RequestDetailResponse> list(AuthenticatedUser actor, RequestStatus status,
            ResourceType type, UUID orgId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        OrgScope scope = listScopeOrgId(actor, orgId);
        Specification<Request> spec = Specification.unrestricted();
        if (!scope.isUnrestricted()) {
            spec = spec.and(RequestSpecs.orgIn(scope.orgIds()));
        }
        if (status != null) {
            spec = spec.and(RequestSpecs.status(status));
        }
        if (type != null) {
            spec = spec.and(RequestSpecs.type(type));
        }
        Page<Request> result = requestRepository.findAll(spec, pageable);
        return PageResponse.of(assembler.toDetails(result.getContent(), actor), result);
    }

    @Transactional(readOnly = true)
    public RequestDetailResponse get(AuthenticatedUser actor, UUID requestId) {
        return assembler.toDetail(findReadable(actor, requestId), actor);
    }

    @Transactional
    public RequestDetailResponse approve(AuthenticatedUser actor, UUID requestId,
            ApproveRequestRequest form, String ip) {
        Request request = findWritableWithLock(actor, requestId);
        decide(actor, request, form, ip, false, "");
        return assembler.toDetail(request, actor);
    }

    /**
     * Approves a request its approver has just submitted, in the submission's
     * transaction: the same decision the approve endpoint makes, run by the
     * same code, so that "submitted and approved at once" cannot drift from
     * "approved". The caller has already established that {@code actor} may
     * decide in the request's organisation.
     *
     * <p>Field errors come back under {@code approval.}, where the form put
     * them.</p>
     */
    @Transactional
    public void approveOnSubmission(AuthenticatedUser actor, Request request,
            ApproveRequestRequest form, String ip) {
        decide(actor, request, form, ip, true, "approval.");
    }

    /**
     * The approval decision, for a request the caller holds (row-locked, or
     * created in this transaction).
     */
    private void decide(AuthenticatedUser actor, Request request, ApproveRequestRequest form,
            String ip, boolean submittedByReviewer, String fieldPrefix) {
        requireSubmitted(request);
        // Approval creates a resource inside the workspace, so the workspace has
        // to still be there. Deleting a workspace cancels its in-flight requests,
        // but a request submitted concurrently with that delete commits after the
        // sweep has already run and stays SUBMITTED — this is what stops it from
        // being approved into a workspace nobody can reach.
        if (workspaceRepository.findByIdAndDeletedAtIsNull(request.getWorkspaceId()).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.WORKSPACE_DELETED,
                    "워크스페이스가 삭제되었습니다",
                    "삭제된 워크스페이스에는 리소스를 만들 수 없습니다. 이 신청은 반려해 주세요.");
        }
        RequestTypeHandler handler = handlerFor(request);
        boolean perRecipient = recipientService.hasRecipients(request);

        List<FieldValidationError> errors = new ArrayList<>();
        if (form.grantedStartDate() != null && form.grantedEndDate() != null
                && form.grantedEndDate().isBefore(form.grantedStartDate())) {
            errors.add(new FieldValidationError("grantedEndDate", "종료일은 시작일 이후여야 합니다."));
        }
        handler.validateApprove(request, form, errors);
        if (perRecipient) {
            if (!handler.supportsRecipients()) {
                throw new IllegalStateException("request " + request.getId()
                        + " has recipients but its kind makes one resource");
            }
            handler.validateApproveForRecipients(request, form, errors);
        } else if (workspaceMemberRepository.findByWorkspaceIdAndUserId(request.getWorkspaceId(),
                request.getRequesterId()).isEmpty()
                || userRepository.findById(request.getRequesterId())
                        .filter(user -> user.getStatus() == UserStatus.ACTIVE).isEmpty()) {
            // The resource is created with its requester as its owner, so approval
            // needs that person to still be someone who can hold a grant here. If
            // they left the workspace or the platform meanwhile, the request is no
            // longer approvable and the reviewer rejects it instead — inventing a
            // different owner would be the platform guessing whose resource this is.
            // A request with recipients owns nothing through its requester, so
            // the check is each recipient's instead, made when theirs is created.
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.REQUEST_REQUESTER_INELIGIBLE,
                    "신청자가 더 이상 이 워크스페이스의 활성 구성원이 아닙니다",
                    "승인하면 이 리소스의 소유자가 될 사람이 없습니다. 이 신청은 반려해 주세요.");
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(fieldPrefix.isEmpty() ? errors : errors.stream()
                    .map(error -> new FieldValidationError(fieldPrefix + error.field(), error.message()))
                    .toList());
        }

        Map<String, Object> auditArgs = new LinkedHashMap<>();
        auditArgs.put("type", request.getResourceType().name());
        if (submittedByReviewer) {
            // The same person asked and decided. Not "automatic": a person
            // decided, and the reviewer on the record is that person.
            auditArgs.put("submittedByReviewer", true);
        }
        Map<String, Object> notifyArgs = new LinkedHashMap<>();
        notifyArgs.put("requestId", request.getPublicId());
        if (perRecipient) {
            // The decision and the grant, once. Every recipient's resource is
            // made after this commits, each in its own transaction.
            // Recipients are placed first so the grant can be sized by how many
            // resources it will actually make. Lock order stays request row,
            // then this request's recipients, then whatever the kind's grant
            // takes (the gateway generation row for a key); the materializer
            // never holds a recipient of a request that is still undecided.
            kr.ac.pusan.pickle.request.RequestRecipientService.Settlement settled =
                    recipientService.settleAtApproval(request);
            auditArgs.putAll(requestApproval.applyForRecipients(request, handler, form, actor.id(),
                    settled.queued() + settled.pendingJoin()));
            auditArgs.put("recipientsQueued", settled.queued());
            auditArgs.put("recipientsPendingJoin", settled.pendingJoin());
            auditArgs.put("recipientsSkipped", settled.skipped());
            // No type on the notice: the per-kind wording speaks to the owner
            // of the one resource ("generation starts", "issue your key"), and
            // the requester of a many-person request owns none of them. What
            // they get instead is how the recipients were settled.
            notifyArgs.put("resourceName", request.getDisplayName());
            notifyArgs.put("recipientTotal", settled.queued() + settled.pendingJoin() + settled.skipped());
            notifyArgs.put("queued", settled.queued());
            notifyArgs.put("pendingJoin", settled.pendingJoin());
            notifyArgs.put("skipped", settled.skipped());
        } else {
            // The decision, the resource and its first grant, shared with the path
            // that approves without a reviewer.
            RequestTypeHandler.Materialized created =
                    requestApproval.apply(request, handler, form, actor.id(), actor);
            auditArgs.putAll(created.auditArgs());
            notifyArgs.put("type", request.getResourceType().name());
            notifyArgs.put("resourceName", created.resourceName());
            notifyArgs.putAll(created.notificationArgs());
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.REQUEST_APPROVE,
                "request", request.getPublicId(), auditArgs, ip);
        // The comment is the reviewer speaking to the requester. When they are
        // the same person it would only quote the approver back to themselves.
        String reviewComment = submittedByReviewer ? null : Texts.blankToNull(form.comment());
        if (reviewComment != null) {
            notifyArgs.put("comment", reviewComment);
        }
        // In-tx insert: the notice exists iff the approval committed. A request
        // submitted and approved in one step has nobody to tell about the
        // decision: the requester is the person who just decided. One with
        // recipients still gets the summary, since how its recipients were
        // settled is news even to the person who decided it.
        if (!submittedByReviewer || perRecipient) {
            notificationService.publish(request.getRequesterId(), NotificationEvent.REQUEST_APPROVED,
                    notifyArgs, null);
        }
    }

    @Transactional
    public RequestDetailResponse reject(AuthenticatedUser actor, UUID requestId,
            RejectRequestRequest form, String ip) {
        Request request = findWritableWithLock(actor, requestId);
        requireSubmitted(request);
        reviewRepository.save(RequestReview.reject(request.getId(), actor.id(), form.comment().strip()));
        request.setStatus(RequestStatus.REJECTED);
        recipientService.closeUndecided(request);
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.REQUEST_REJECT,
                "request", request.getPublicId(),
                Map.of("workspaceId", auditIds.workspace(request.getWorkspaceId())), ip);
        notificationService.publish(request.getRequesterId(), NotificationEvent.REQUEST_REJECTED,
                Map.of("requestId", request.getPublicId(), "comment", form.comment().strip(),
                        "type", request.getResourceType().name()), null);
        return assembler.toDetail(request, actor);
    }

    /**
     * Queues a recipient whose creation failed for another attempt. Same
     * lookup and scope as a decision: the org tier acts where it operates, and
     * a request outside answers 404.
     */
    @Transactional
    public RequestDetailResponse retryRecipient(AuthenticatedUser actor, UUID requestId,
            UUID recipientId, String ip) {
        Request request = findWritableWithLock(actor, requestId);
        kr.ac.pusan.pickle.request.RequestRecipient recipient = recipientService.retry(request, recipientId);
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.REQUEST_RECIPIENT_RETRY,
                "request", request.getPublicId(),
                Map.of("recipientId", recipient.getPublicId(), "attempts", recipient.getAttempts()), ip);
        return assembler.toDetail(request, actor);
    }

    /** Read scope for the request queue. */
    private OrgScope listScopeOrgId(AuthenticatedUser actor, UUID orgId) {
        Long requested = orgId == null ? null
                : orgRepository.findByPublicId(orgId).map(Org::getId).orElse(null);
        return AdminOrgScope.read(actor, orgId, requested);
    }

    /**
     * Read lookup (request detail, approval context). The org tier reads the
     * requests of every organisation it holds a role in, a read-only role
     * included.
     *
     * <p>Split from {@link #findWritableWithLock} so the two questions cannot be
     * answered by one condition: deciding a request needs a role that may act,
     * reading one does not.
     */
    Request findReadable(AuthenticatedUser actor, UUID requestId) {
        Request request = requestRepository.findByPublicId(requestId).orElse(null);
        if (request == null) {
            throw requestNotFound();
        }
        if (actor.role().isOrgTier() && !actor.reads(request.getOrgId())) {
            throw requestNotFound();
        }
        return request;
    }

    /** Write lookup (approve, reject), row-locked. Same 404 masking. */
    private Request findWritableWithLock(AuthenticatedUser actor, UUID requestId) {
        Request request = requestRepository.findWithLockByPublicId(requestId).orElse(null);
        requireSameOrg(actor, request);
        return request;
    }

    private static void requireSameOrg(AuthenticatedUser actor, Request request) {
        if (request == null
                || (actor.role().isOrgTier() && !actor.operates(request.getOrgId()))) {
            throw requestNotFound();
        }
    }

    private static ApiException requestNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 신청이 존재하지 않습니다.");
    }

    private RequestTypeHandler handlerFor(Request request) {
        RequestTypeHandler handler = handlers.get(request.getResourceType());
        if (handler == null) {
            throw new IllegalStateException(
                    "No handler for resource type " + request.getResourceType());
        }
        return handler;
    }

    private static void requireSubmitted(Request request) {
        if (request.getStatus() != RequestStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.REQUEST_ALREADY_DECIDED,
                    "이미 처리된 신청입니다", "이 신청은 이미 승인, 반려 또는 취소되었습니다.");
        }
    }

}
