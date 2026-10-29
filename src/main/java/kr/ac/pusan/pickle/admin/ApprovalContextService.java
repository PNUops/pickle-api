package kr.ac.pusan.pickle.admin;

import java.util.List;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.Applicant;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.WorkspacePanel;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.HistoryEntry;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.MemberBrief;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.Resources;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.VmContext;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.request.RequestRepository;
import kr.ac.pusan.pickle.request.RequestReview;
import kr.ac.pusan.pickle.request.RequestReviewRepository;
import kr.ac.pusan.pickle.request.RequestStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the {@code ApprovalContext} decision-support payload:
 * applicant history and current resources, workspace panel, prior decisions, and
 * org headroom with threshold warnings from {@code settings} plus the derived
 * Korean guidance line. "Active" VMs are all non-deleted rows — they hold
 * allocation regardless of power state.
 */
@Service
public class ApprovalContextService {

    @org.springframework.beans.factory.annotation.Autowired
    private StudentSupportPolicy studentSupportPolicy;

    // Aliases kept for existing callers/tests; the single home of the
    // headroom math and guidance wording is OrgHeadroomService.
    static final String GUIDANCE_AMPLE = OrgHeadroomService.GUIDANCE_AMPLE;
    static final String GUIDANCE_MEMORY = OrgHeadroomService.GUIDANCE_MEMORY;
    static final String GUIDANCE_VCPU = OrgHeadroomService.GUIDANCE_VCPU;
    static final String GUIDANCE_BOTH = OrgHeadroomService.GUIDANCE_BOTH;

    private static final int HISTORY_LIMIT = 20;

    private final ApprovalService approvalService;
    private final RequestRepository requestRepository;
    private final RequestReviewRepository reviewRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;
    private final Map<ResourceType, ApprovalContextContributor> contributors;
    private final VmApprovalContextContributor vmContributor;
    private final OrgRepository orgRepository;
    private final AdminRequestGeneratedResources generatedResources;
    private final Clock clock;
    private final kr.ac.pusan.pickle.request.InstitutionRequestAccess institutionRequests;
    private final kr.ac.pusan.pickle.security.institution.InstitutionAuthorization authorization;

    public ApprovalContextService(ApprovalService approvalService, RequestRepository requestRepository,
            RequestReviewRepository reviewRepository,
            WorkspaceRepository workspaceRepository, WorkspaceMemberRepository workspaceMemberRepository,
            UserRepository userRepository, List<ApprovalContextContributor> contributors,
            VmApprovalContextContributor vmContributor, OrgRepository orgRepository,
            AdminRequestGeneratedResources generatedResources, Clock clock, kr.ac.pusan.pickle.request.InstitutionRequestAccess institutionRequests,
            kr.ac.pusan.pickle.security.institution.InstitutionAuthorization authorization) {
        this.institutionRequests = institutionRequests; this.authorization = authorization;
        this.approvalService = approvalService;
        this.requestRepository = requestRepository;
        this.reviewRepository = reviewRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
        this.contributors = contributors.stream().collect(Collectors.toUnmodifiableMap(
                ApprovalContextContributor::type, Function.identity()));
        this.vmContributor = vmContributor;
        this.orgRepository = orgRepository;
        this.generatedResources = generatedResources;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ApprovalContextResponse context(AuthenticatedUser actor, UUID requestId) {
        Request request = approvalService.findReadable(actor, requestId);
        // The requester row always exists: accounts are soft-deleted only
        // (WITHDRAWN + later anonymization keep the row). The requester identity
        // is a request reference; broader profile fields have their own work check.
        User applicant = userRepository.findById(request.getRequesterId()).orElseThrow();
        Workspace workspace = workspaceRepository.findById(request.getWorkspaceId()).orElseThrow();

        List<Long> applicantWorkspaceIds = applicantWorkspaceIds(request.getRequesterId());
        VmContext legacyVm = vmContributor.contribute(request, applicantWorkspaceIds, actor).vm();
        ApprovalContextContributor contributor = contributors.get(request.getResourceType());
        if (contributor == null) {
            throw new IllegalStateException("approval context contributor is missing for "
                    + request.getResourceType());
        }
        ApprovalContextContributor.Contribution contribution = request.getResourceType() == ResourceType.VM
                ? ApprovalContextContributor.Contribution.vm(legacyVm)
                : contributor.contribute(request, applicantWorkspaceIds, actor);
        Org org = orgRepository.findById(request.getOrgId()).orElseThrow();
        Instant observedAt = clock.instant();
        return new ApprovalContextResponse(
                request.getResourceType(),
                applicantPanel(request, applicant, actor),
                legacyVm.applicantResources(),
                workspacePanel(workspace, legacyVm.workspaceResources(), actor),
                history(request, actor),
                legacyVm.orgHeadroom(),
                legacyVm.guidance(),
                contribution.vm(),
                contribution.llmKey(), contribution.gpu(), contribution.domain(), org.getPublicId(), org.getName(),
                observedAt, generatedResources.of(request, observedAt, actor),
                generatedResources.recipientUserReadFlags(request, actor));
    }

    private Applicant applicantPanel(Request request, User applicant, AuthenticatedUser actor) {
        return new Applicant(
                applicant.getPublicId(),
                applicant.getName(),
                applicant.getEmail(),
                canReadUser(actor, applicant) ? applicant.getCreatedAt() : null,
                authorizedCount(actor, request.getRequesterId(), RequestStatus.APPROVED),
                authorizedCount(actor, request.getRequesterId(), RequestStatus.REJECTED),
                canReadUser(actor, applicant));
    }

    private long authorizedCount(AuthenticatedUser actor, long requester, RequestStatus status) {
        return requestRepository.count(institutionRequests.readScope(actor).and((root, query, cb) -> cb.and(
                cb.equal(root.get("requesterId"), requester), cb.equal(root.get("status"), status))));
    }

    private List<Long> applicantWorkspaceIds(Long userId) {
        return workspaceMemberRepository.findWithWorkspaceByUserId(userId).stream()
                .map(m -> m.getWorkspace().getId())
                .toList();
    }

    private WorkspacePanel workspacePanel(Workspace workspace, Resources resources, AuthenticatedUser actor) {
        List<WorkspaceMember> members = workspaceMemberRepository.findByWorkspaceIdOrderByIdAsc(workspace.getId());
        Map<Long, User> users = userRepository
                .findAllById(members.stream().map(WorkspaceMember::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        List<MemberBrief> memberBriefs = members.stream()
                .map(m -> new MemberBrief(
                        users.containsKey(m.getUserId()) ? users.get(m.getUserId()).getPublicId() : null,
                        users.containsKey(m.getUserId()) ? users.get(m.getUserId()).getName() : "탈퇴 회원",
                        m.getRole(), users.containsKey(m.getUserId()) ? users.get(m.getUserId()).getStatus() : UserStatus.WITHDRAWN,
                        users.containsKey(m.getUserId()) && canReadUser(actor, users.get(m.getUserId()))))
                .toList();
        return new WorkspacePanel(workspace.getPublicId(), workspace.getName(), workspace.getKind(), memberBriefs,
                resources.activeVms(), resources.totals(), workspace.getDeletedAt() == null && (InstitutionLlmAccess.systemRead(actor) || authorization.workspaceTarget(workspace.getId()).map(target -> authorization.decision(actor,
                        kr.ac.pusan.pickle.security.institution.InstitutionWork.WORKSPACE_SUPPORT_READ, target).allowed()).orElse(false)));
    }

    /** The same three states the request detail reports, for the history panel. */
    private static String reviewerName(RequestReview review, User reviewer) {
        if (review == null) {
            return null;
        }
        if (review.getReviewerId() == null) {
            return "자동 승인";
        }
        return reviewer != null ? reviewer.getName() : "탈퇴 회원";
    }

    private List<HistoryEntry> history(Request request, AuthenticatedUser actor) {
        List<Request> prior = requestRepository.findAll(institutionRequests.readScope(actor).and((root, query, cb) -> cb.and(
                cb.equal(root.get("requesterId"), request.getRequesterId()), cb.equal(root.get("workspaceId"), request.getWorkspaceId()),
                cb.notEqual(root.get("id"), request.getId()))), PageRequest.of(0, HISTORY_LIMIT, Sort.by(Sort.Direction.DESC, "id"))).getContent();
        if (prior.isEmpty()) {
            return List.of();
        }
        Map<Long, RequestReview> reviews = reviewRepository
                .findByRequestIdIn(prior.stream().map(Request::getId).toList())
                .stream().collect(Collectors.toMap(RequestReview::getRequestId, Function.identity()));
        // Null reviewer ids are automatic approvals and there is nobody to look
        // up for them. The batched lookup on the requester side already filters;
        // this one did not, because until now the column could not be null.
        Map<Long, User> reviewers = userRepository
                .findAllById(reviews.values().stream().map(RequestReview::getReviewerId)
                        .filter(java.util.Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, Org> orgs = orgRepository.findAllById(prior.stream().map(Request::getOrgId).distinct().toList())
                .stream().collect(Collectors.toMap(Org::getId, Function.identity()));
        return prior.stream()
                .map(r -> {
                    RequestReview review = reviews.get(r.getId());
                    User reviewer = review != null && review.getReviewerId() != null
                            ? reviewers.get(review.getReviewerId()) : null;
                    return new HistoryEntry(r.getPublicId(), r.getResourceType(), r.getDisplayName(),
                            r.getCreatedAt(), r.getStatus(),
                            review != null ? review.getDecision() : null,
                            review != null ? review.getComment() : null,
                            reviewerName(review, reviewer), orgs.get(r.getOrgId()).getPublicId(),
                            institutionRequests.read(actor, r));
                })
                .toList();
    }

    private boolean canReadUser(AuthenticatedUser actor, User user) {
        return studentSupportPolicy.canReadStudentSupport(actor) && (actor.role().isSysTier() || !user.getRole().isSysTier());
    }
}
