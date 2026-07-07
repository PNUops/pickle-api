package kr.ac.pusan.pickle.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.Applicant;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.Capacity;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.GroupPanel;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.HistoryEntry;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.MemberBrief;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.OrgHeadroom;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.Resources;
import kr.ac.pusan.pickle.admin.dto.ResourceTotalsResponse;
import kr.ac.pusan.pickle.admin.dto.VmBriefResponse;
import kr.ac.pusan.pickle.group.Group;
import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.group.GroupRepository;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.NodeStatus;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.vmrequest.VmRequest;
import kr.ac.pusan.pickle.vmrequest.VmRequestRepository;
import kr.ac.pusan.pickle.vmrequest.VmRequestReview;
import kr.ac.pusan.pickle.vmrequest.VmRequestReviewRepository;
import kr.ac.pusan.pickle.vmrequest.VmRequestStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the {@code ApprovalContext} decision-support payload (docs/plan/11):
 * applicant history and current resources, group panel, prior decisions, and
 * org headroom with threshold warnings from {@code settings} plus the derived
 * Korean guidance line. "Active" VMs are all non-deleted rows — they hold
 * allocation regardless of power state.
 */
@Service
public class ApprovalContextService {

    static final String GUIDANCE_AMPLE = "여유가 충분합니다. 요청 스펙 그대로 승인해도 무리가 없습니다.";
    static final String GUIDANCE_MEMORY = "메모리 여유가 부족해 신중한 승인이 필요합니다.";
    static final String GUIDANCE_VCPU = "vCPU 오버커밋 비율이 높아 신중한 승인이 필요합니다.";
    static final String GUIDANCE_BOTH = "vCPU와 메모리 여유가 모두 부족해 신중한 승인이 필요합니다.";

    private static final int HISTORY_LIMIT = 20;
    private static final double DEFAULT_VCPU_OVERCOMMIT_WARN = 3.0;
    private static final double DEFAULT_MEMORY_USAGE_WARN = 0.8;

    private final ApprovalService approvalService;
    private final VmRequestRepository requestRepository;
    private final VmRequestReviewRepository reviewRepository;
    private final VmRepository vmRepository;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final NodeRepository nodeRepository;
    private final SettingsService settingsService;

    public ApprovalContextService(ApprovalService approvalService, VmRequestRepository requestRepository,
            VmRequestReviewRepository reviewRepository, VmRepository vmRepository,
            GroupRepository groupRepository, GroupMemberRepository groupMemberRepository,
            UserRepository userRepository, NodeRepository nodeRepository, SettingsService settingsService) {
        this.approvalService = approvalService;
        this.requestRepository = requestRepository;
        this.reviewRepository = reviewRepository;
        this.vmRepository = vmRepository;
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.userRepository = userRepository;
        this.nodeRepository = nodeRepository;
        this.settingsService = settingsService;
    }

    @Transactional(readOnly = true)
    public ApprovalContextResponse context(AuthenticatedUser actor, long requestId) {
        VmRequest request = approvalService.findScoped(actor, requestId);
        User applicant = userRepository.findById(request.getRequesterId()).orElse(null);
        Group group = groupRepository.findById(request.getGroupId()).orElseThrow();

        OrgHeadroom headroom = orgHeadroom(request.getOrgId());
        return new ApprovalContextResponse(
                applicantPanel(request, applicant),
                applicantResources(request.getRequesterId()),
                groupPanel(group),
                history(request),
                headroom,
                guidance(headroom.warnings()));
    }

    private Applicant applicantPanel(VmRequest request, User applicant) {
        return new Applicant(
                request.getRequesterId(),
                applicant != null ? applicant.getName() : "탈퇴 회원",
                applicant != null ? applicant.getEmail() : "",
                applicant != null ? applicant.getCreatedAt() : null,
                requestRepository.countByRequesterIdAndStatus(request.getRequesterId(), VmRequestStatus.APPROVED),
                requestRepository.countByRequesterIdAndStatus(request.getRequesterId(), VmRequestStatus.REJECTED));
    }

    private Resources applicantResources(Long userId) {
        List<Long> groupIds = groupMemberRepository.findWithGroupByUserId(userId).stream()
                .map(m -> m.getGroup().getId())
                .toList();
        List<Vm> activeVms = groupIds.isEmpty()
                ? List.of()
                : vmRepository.findActiveByGroupIdIn(groupIds, VmStatus.DELETED);
        return new Resources(briefs(activeVms), ResourceTotalsResponse.of(activeVms));
    }

    private GroupPanel groupPanel(Group group) {
        List<GroupMember> members = groupMemberRepository.findByGroupIdOrderByIdAsc(group.getId());
        Map<Long, User> users = userRepository
                .findAllById(members.stream().map(GroupMember::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        List<MemberBrief> memberBriefs = members.stream()
                .map(m -> new MemberBrief(m.getUserId(),
                        users.containsKey(m.getUserId()) ? users.get(m.getUserId()).getName() : "탈퇴 회원",
                        m.getRole()))
                .toList();
        List<Vm> activeVms = vmRepository.findActiveByGroupIdIn(List.of(group.getId()), VmStatus.DELETED);
        return new GroupPanel(group.getId(), group.getName(), group.getKind(), memberBriefs,
                briefs(activeVms), ResourceTotalsResponse.of(activeVms));
    }

    private List<HistoryEntry> history(VmRequest request) {
        List<VmRequest> prior = requestRepository.findHistory(request.getRequesterId(), request.getGroupId(),
                request.getId(), PageRequest.of(0, HISTORY_LIMIT, Sort.by(Sort.Direction.DESC, "id")));
        if (prior.isEmpty()) {
            return List.of();
        }
        Map<Long, VmRequestReview> reviews = reviewRepository
                .findByRequestIdIn(prior.stream().map(VmRequest::getId).toList())
                .stream().collect(Collectors.toMap(VmRequestReview::getRequestId, Function.identity()));
        Map<Long, User> reviewers = userRepository
                .findAllById(reviews.values().stream().map(VmRequestReview::getReviewerId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return prior.stream()
                .map(r -> {
                    VmRequestReview review = reviews.get(r.getId());
                    User reviewer = review != null ? reviewers.get(review.getReviewerId()) : null;
                    return new HistoryEntry(r.getId(), r.getCreatedAt(), r.getStatus(),
                            review != null ? review.getDecision() : null,
                            review != null ? review.getComment() : null,
                            reviewer != null ? reviewer.getName() : null);
                })
                .toList();
    }

    private OrgHeadroom orgHeadroom(Long orgId) {
        List<Vm> orgVms = vmRepository.findActiveByOrgId(orgId, VmStatus.DELETED);
        ResourceTotalsResponse allocated = ResourceTotalsResponse.of(orgVms);
        List<Node> nodes = nodeRepository.findByStatusOrderByIdAsc(NodeStatus.ACTIVE);
        long cpuThreads = nodes.stream().mapToLong(Node::getCpuThreads).sum();
        long memoryMb = nodes.stream().mapToLong(Node::getMemoryMb).sum();

        double vcpuRatio = cpuThreads == 0 ? 0.0 : round2((double) allocated.vcpu() / cpuThreads);
        double memoryRatio = memoryMb == 0 ? 0.0 : round2((double) allocated.memoryMb() / memoryMb);
        double vcpuWarn = settingsService.decimal(SettingsService.VCPU_OVERCOMMIT_WARN,
                DEFAULT_VCPU_OVERCOMMIT_WARN);
        double memoryWarn = settingsService.decimal(SettingsService.MEMORY_USAGE_WARN,
                DEFAULT_MEMORY_USAGE_WARN);

        List<String> warnings = new ArrayList<>();
        if (vcpuRatio >= vcpuWarn) {
            warnings.add("vCPU 오버커밋 비율이 경고 임계값을 초과했습니다 (%.2f ≥ %.2f).".formatted(vcpuRatio, vcpuWarn));
        }
        if (memoryRatio >= memoryWarn) {
            warnings.add("메모리 할당 비율이 경고 임계값을 초과했습니다 (%.2f ≥ %.2f).".formatted(memoryRatio, memoryWarn));
        }
        return new OrgHeadroom(allocated, new Capacity(cpuThreads, memoryMb), vcpuRatio, memoryRatio,
                List.copyOf(warnings));
    }

    private static String guidance(List<String> warnings) {
        boolean vcpu = warnings.stream().anyMatch(w -> w.startsWith("vCPU"));
        boolean memory = warnings.stream().anyMatch(w -> w.startsWith("메모리"));
        if (vcpu && memory) {
            return GUIDANCE_BOTH;
        }
        if (memory) {
            return GUIDANCE_MEMORY;
        }
        if (vcpu) {
            return GUIDANCE_VCPU;
        }
        return GUIDANCE_AMPLE;
    }

    private static List<VmBriefResponse> briefs(List<Vm> vms) {
        return vms.stream().map(VmBriefResponse::from).toList();
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
