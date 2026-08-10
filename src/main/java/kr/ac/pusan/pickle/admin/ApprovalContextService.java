package kr.ac.pusan.pickle.admin;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.Applicant;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.Capacity;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.WorkspacePanel;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.HistoryEntry;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.MemberBrief;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.OrgHeadroom;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.Resources;
import kr.ac.pusan.pickle.admin.dto.ResourceTotalsResponse;
import kr.ac.pusan.pickle.admin.dto.VmBriefResponse;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
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
    private final VmRepository vmRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;
    private final OrgHeadroomService orgHeadroomService;

    public ApprovalContextService(ApprovalService approvalService, RequestRepository requestRepository,
            RequestReviewRepository reviewRepository, VmRepository vmRepository,
            WorkspaceRepository workspaceRepository, WorkspaceMemberRepository workspaceMemberRepository,
            UserRepository userRepository, OrgHeadroomService orgHeadroomService) {
        this.approvalService = approvalService;
        this.requestRepository = requestRepository;
        this.reviewRepository = reviewRepository;
        this.vmRepository = vmRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
        this.orgHeadroomService = orgHeadroomService;
    }

    @Transactional(readOnly = true)
    public ApprovalContextResponse context(AuthenticatedUser actor, long requestId) {
        Request request = approvalService.findScoped(actor, requestId);
        // The requester row always exists: accounts are soft-deleted only
        // (WITHDRAWN + later anonymization keep the row), so
        // applicant.signupAt/email are always non-null per contract.
        User applicant = userRepository.findById(request.getRequesterId()).orElseThrow();
        Workspace workspace = workspaceRepository.findById(request.getWorkspaceId()).orElseThrow();

        OrgHeadroomService.HeadroomResult headroom = orgHeadroomService.headroom(request.getOrgId());
        return new ApprovalContextResponse(
                applicantPanel(request, applicant),
                applicantResources(request.getRequesterId()),
                workspacePanel(workspace),
                history(request),
                new OrgHeadroom(headroom.allocated(),
                        new Capacity(headroom.capacityVcpu(), headroom.capacityMemoryMb(),
                                headroom.capacityDiskGb()),
                        headroom.vcpuRatio(), headroom.memoryRatio(), headroom.diskRatio(),
                        headroom.warnings()),
                headroom.guidance());
    }

    private Applicant applicantPanel(Request request, User applicant) {
        return new Applicant(
                applicant.getId(),
                applicant.getName(),
                applicant.getEmail(),
                applicant.getCreatedAt(),
                requestRepository.countByRequesterIdAndStatus(request.getRequesterId(), RequestStatus.APPROVED),
                requestRepository.countByRequesterIdAndStatus(request.getRequesterId(), RequestStatus.REJECTED));
    }

    private Resources applicantResources(Long userId) {
        List<Long> workspaceIds = workspaceMemberRepository.findWithWorkspaceByUserId(userId).stream()
                .map(m -> m.getWorkspace().getId())
                .toList();
        List<Vm> activeVms = workspaceIds.isEmpty()
                ? List.of()
                : vmRepository.findActiveByWorkspaceIdIn(workspaceIds, VmStatus.DELETED);
        return new Resources(briefs(activeVms), ResourceTotalsResponse.of(activeVms));
    }

    private WorkspacePanel workspacePanel(Workspace workspace) {
        List<WorkspaceMember> members = workspaceMemberRepository.findByWorkspaceIdOrderByIdAsc(workspace.getId());
        Map<Long, User> users = userRepository
                .findAllById(members.stream().map(WorkspaceMember::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        List<MemberBrief> memberBriefs = members.stream()
                .map(m -> new MemberBrief(m.getUserId(),
                        users.containsKey(m.getUserId()) ? users.get(m.getUserId()).getName() : "탈퇴 회원",
                        m.getRole()))
                .toList();
        List<Vm> activeVms = vmRepository.findActiveByWorkspaceIdIn(List.of(workspace.getId()), VmStatus.DELETED);
        return new WorkspacePanel(workspace.getId(), workspace.getName(), workspace.getKind(), memberBriefs,
                briefs(activeVms), ResourceTotalsResponse.of(activeVms));
    }

    private List<HistoryEntry> history(Request request) {
        List<Request> prior = requestRepository.findHistory(request.getRequesterId(), request.getWorkspaceId(),
                request.getId(), PageRequest.of(0, HISTORY_LIMIT, Sort.by(Sort.Direction.DESC, "id")));
        if (prior.isEmpty()) {
            return List.of();
        }
        Map<Long, RequestReview> reviews = reviewRepository
                .findByRequestIdIn(prior.stream().map(Request::getId).toList())
                .stream().collect(Collectors.toMap(RequestReview::getRequestId, Function.identity()));
        Map<Long, User> reviewers = userRepository
                .findAllById(reviews.values().stream().map(RequestReview::getReviewerId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return prior.stream()
                .map(r -> {
                    RequestReview review = reviews.get(r.getId());
                    User reviewer = review != null ? reviewers.get(review.getReviewerId()) : null;
                    return new HistoryEntry(r.getId(), r.getCreatedAt(), r.getStatus(),
                            review != null ? review.getDecision() : null,
                            review != null ? review.getComment() : null,
                            reviewer != null ? reviewer.getName() : null);
                })
                .toList();
    }

    private static List<VmBriefResponse> briefs(List<Vm> vms) {
        return vms.stream().map(VmBriefResponse::from).toList();
    }
}
