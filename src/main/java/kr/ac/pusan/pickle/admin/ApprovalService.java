package kr.ac.pusan.pickle.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.ResourceAccessGrant;
import kr.ac.pusan.pickle.access.ResourceAccessGrantRepository;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.ApproveRequestRequest;
import kr.ac.pusan.pickle.admin.dto.RejectRequestRequest;
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
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.request.RequestAssembler;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Admin approval flow (contract tag {@code admin}, vm-requests subset).
 * ORG_ADMIN is hard-scoped to their own org — requests of other orgs answer
 * 404 (existence stays private, per contract). Approval is a single
 * transaction that writes intent only: review row + CREATING vm row + a
 * JobRunr provisioning job; no Proxmox call happens here.
 */
@Service
public class ApprovalService {

    private final RequestRepository requestRepository;
    private final RequestReviewRepository reviewRepository;
    private final RequestAssembler assembler;
    private final Map<ResourceType, RequestTypeHandler> handlers;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ResourceAccessGrantRepository grantRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public ApprovalService(RequestRepository requestRepository, RequestReviewRepository reviewRepository,
            RequestAssembler assembler, List<RequestTypeHandler> handlers,
            WorkspaceMemberRepository workspaceMemberRepository,
            WorkspaceRepository workspaceRepository,
            ResourceAccessGrantRepository grantRepository, UserRepository userRepository,
            AuditService auditService, NotificationService notificationService) {
        this.requestRepository = requestRepository;
        this.reviewRepository = reviewRepository;
        this.assembler = assembler;
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(RequestTypeHandler::type, Function.identity()));
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.workspaceRepository = workspaceRepository;
        this.grantRepository = grantRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional(readOnly = true)
    public PageResponse<RequestDetailResponse> list(AuthenticatedUser actor, RequestStatus status,
            ResourceType type, Long orgId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        // Org tier is always pinned to their own org; orgId is sys-tier-only.
        Long scopedOrgId = actor.role().isOrgTier() ? actor.orgId() : orgId;
        if (actor.role().isOrgTier() && scopedOrgId == null) {
            // Defensive: an org-tier actor without a managed org sees nothing.
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "관리 기관이 지정되지 않은 계정입니다.");
        }
        Specification<Request> spec = Specification.unrestricted();
        if (scopedOrgId != null) {
            spec = spec.and(RequestSpecs.org(scopedOrgId));
        }
        if (status != null) {
            spec = spec.and(RequestSpecs.status(status));
        }
        if (type != null) {
            spec = spec.and(RequestSpecs.type(type));
        }
        Page<Request> result = requestRepository.findAll(spec, pageable);
        return PageResponse.of(assembler.toDetails(result.getContent()), result);
    }

    @Transactional(readOnly = true)
    public RequestDetailResponse get(AuthenticatedUser actor, long requestId) {
        return assembler.toDetail(findScoped(actor, requestId));
    }

    @Transactional
    public RequestDetailResponse approve(AuthenticatedUser actor, long requestId,
            ApproveRequestRequest form, String ip) {
        Request request = findScopedWithLock(actor, requestId);
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

        List<FieldValidationError> errors = new ArrayList<>();
        if (form.grantedStartDate() != null && form.grantedEndDate() != null
                && form.grantedEndDate().isBefore(form.grantedStartDate())) {
            errors.add(new FieldValidationError("grantedEndDate", "종료일은 시작일 이후여야 합니다."));
        }
        handler.validateApprove(request, form, errors);
        // The resource is created with its requester as its owner, so approval
        // needs that person to still be someone who can hold a grant here. If
        // they left the workspace or the platform meanwhile, the request is no
        // longer approvable and the reviewer rejects it instead — inventing a
        // different owner would be the platform guessing whose resource this is.
        if (workspaceMemberRepository.findByWorkspaceIdAndUserId(request.getWorkspaceId(),
                request.getRequesterId()).isEmpty()
                || userRepository.findById(request.getRequesterId())
                        .filter(user -> user.getStatus() == UserStatus.ACTIVE).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.REQUEST_REQUESTER_INELIGIBLE,
                    "신청자가 더 이상 이 워크스페이스의 활성 구성원이 아닙니다",
                    "승인하면 이 리소스의 소유자가 될 사람이 없습니다. 이 신청은 반려해 주세요.");
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }

        reviewRepository.save(RequestReview.approve(request.getId(), actor.id(),
                Texts.blankToNull(form.comment()), form.grantedStartDate(), form.grantedEndDate()));
        request.setStatus(RequestStatus.APPROVED);
        RequestTypeHandler.Materialized created = handler.materialize(request, form, actor);

        // The resource starts private: its requester, and nobody else. Anyone
        // who should reach it is added to its access list afterwards, so a
        // resource is never open by default through a step somebody forgot.
        grantRepository.save(ResourceAccessGrant.forUser(request.getResourceType(),
                created.resourceId(), request.getRequesterId(), ResourceRole.OWNER));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                created.afterCommit().run();
            }
        });

        Map<String, Object> auditArgs = new LinkedHashMap<>();
        auditArgs.put("type", request.getResourceType().name());
        auditArgs.putAll(created.auditArgs());
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.REQUEST_APPROVE,
                "request", request.getId(), auditArgs, ip);
        // In-tx insert: the notice exists iff the approval committed.
        Map<String, Object> notifyArgs = new LinkedHashMap<>();
        notifyArgs.put("requestId", request.getId());
        notifyArgs.put("type", request.getResourceType().name());
        notifyArgs.put("resourceName", created.resourceName());
        String reviewComment = Texts.blankToNull(form.comment());
        if (reviewComment != null) {
            notifyArgs.put("comment", reviewComment);
        }
        notificationService.publish(request.getRequesterId(), NotificationEvent.REQUEST_APPROVED,
                notifyArgs, null);
        return assembler.toDetail(request);
    }

    @Transactional
    public RequestDetailResponse reject(AuthenticatedUser actor, long requestId,
            RejectRequestRequest form, String ip) {
        Request request = findScopedWithLock(actor, requestId);
        requireSubmitted(request);
        reviewRepository.save(RequestReview.reject(request.getId(), actor.id(), form.comment().strip()));
        request.setStatus(RequestStatus.REJECTED);
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.REQUEST_REJECT,
                "request", request.getId(), Map.of("workspaceId", request.getWorkspaceId()), ip);
        notificationService.publish(request.getRequesterId(), NotificationEvent.REQUEST_REJECTED,
                Map.of("requestId", request.getId(), "comment", form.comment().strip(),
                        "type", request.getResourceType().name()), null);
        return assembler.toDetail(request);
    }

    /** Org-scoped lookup: unknown id and other-org requests both answer 404. */
    Request findScoped(AuthenticatedUser actor, long requestId) {
        return scoped(actor, requestRepository.findById(requestId).orElse(null));
    }

    private Request findScopedWithLock(AuthenticatedUser actor, long requestId) {
        return scoped(actor, requestRepository.findWithLockById(requestId).orElse(null));
    }

    private Request scoped(AuthenticatedUser actor, Request request) {
        if (request == null
                || (actor.role().isOrgTier() && !request.getOrgId().equals(actor.orgId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                    "리소스를 찾을 수 없습니다", "해당 신청이 존재하지 않습니다.");
        }
        return request;
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
