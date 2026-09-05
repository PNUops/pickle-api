package kr.ac.pusan.pickle.request;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.OsImage;
import kr.ac.pusan.pickle.inventory.OsImageRepository;
import kr.ac.pusan.pickle.inventory.VmFlavor;
import kr.ac.pusan.pickle.inventory.VmFlavorRepository;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.request.dto.RequestDetailResponse;
import org.jspecify.annotations.Nullable;
import kr.ac.pusan.pickle.request.period.RequestPeriodPreset;
import kr.ac.pusan.pickle.request.period.RequestPeriodPresetRepository;
import kr.ac.pusan.pickle.request.dto.RequestReviewResponse;
import kr.ac.pusan.pickle.llm.LlmKeyRequestDetail;
import kr.ac.pusan.pickle.llm.LlmKeyRequestDetailRepository;
import kr.ac.pusan.pickle.llm.CreditModelPatterns;
import kr.ac.pusan.pickle.llm.dto.LlmKeyRequestSpecResponse;
import kr.ac.pusan.pickle.request.vm.VmRequestDetail;
import kr.ac.pusan.pickle.request.vm.VmRequestDetailRepository;
import kr.ac.pusan.pickle.request.vm.dto.VmRequestSpecResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds contract {@code RequestDetail} payloads: resolves workspace/org/user
 * display names and the review (when decided) with batched lookups, so list
 * pages stay free of per-row queries.
 */
@Component
public class RequestAssembler {

    private final RequestReviewRepository reviewRepository;
    private final VmRequestDetailRepository vmDetailRepository;
    private final LlmKeyRequestDetailRepository llmKeyDetailRepository;
    private final WorkspaceRepository workspaceRepository;
    private final OrgRepository orgRepository;
    private final UserRepository userRepository;
    private final OsImageRepository osImageRepository;
    private final VmFlavorRepository vmFlavorRepository;
    private final NodeRepository nodeRepository;
    private final RequestPeriodPresetRepository periodPresetRepository;
    private final ObjectMapper objectMapper;

    public RequestAssembler(RequestReviewRepository reviewRepository,
            VmRequestDetailRepository vmDetailRepository,
            LlmKeyRequestDetailRepository llmKeyDetailRepository,
            WorkspaceRepository workspaceRepository,
            OrgRepository orgRepository, UserRepository userRepository,
            OsImageRepository osImageRepository, VmFlavorRepository vmFlavorRepository,
            NodeRepository nodeRepository, RequestPeriodPresetRepository periodPresetRepository,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.reviewRepository = reviewRepository;
        this.vmDetailRepository = vmDetailRepository;
        this.llmKeyDetailRepository = llmKeyDetailRepository;
        this.workspaceRepository = workspaceRepository;
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.osImageRepository = osImageRepository;
        this.vmFlavorRepository = vmFlavorRepository;
        this.nodeRepository = nodeRepository;
        this.periodPresetRepository = periodPresetRepository;
    }

    /** 고른 기간의 이름. 직접 적었거나 항목이 사라졌으면 null이다. */
    private static @Nullable String periodName(Map<Long, RequestPeriodPreset> periods,
            @Nullable Long presetId) {
        if (presetId == null) {
            return null;
        }
        RequestPeriodPreset preset = periods.get(presetId);
        return preset == null ? null : preset.getDisplayName();
    }

    public RequestDetailResponse toDetail(Request request) {
        return toDetails(List.of(request)).getFirst();
    }

    public List<RequestDetailResponse> toDetails(List<Request> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        Map<Long, RequestReview> reviews = reviewRepository
                .findByRequestIdIn(requests.stream().map(Request::getId).toList())
                .stream()
                .collect(Collectors.toMap(RequestReview::getRequestId, Function.identity()));
        Map<Long, Workspace> workspaces = byId(workspaceRepository
                .findAllById(ids(requests.stream().map(Request::getWorkspaceId))), Workspace::getId);
        Map<Long, Org> orgs = byId(orgRepository
                .findAllById(ids(requests.stream().map(Request::getOrgId))), Org::getId);
        Map<Long, User> users = byId(userRepository.findAllById(ids(Stream.concat(
                requests.stream().map(Request::getRequesterId),
                reviews.values().stream().map(RequestReview::getReviewerId)))), User::getId);
        // One batch per resource type present on the page. A new type adds a
        // line here and a member on the response; that is the price of
        // reporting each type's own fields under its own name.
        Map<Long, VmRequestDetail> vmDetails = vmDetailRepository
                .findByRequestIdIn(idsOfType(requests, ResourceType.VM)).stream()
                .collect(Collectors.toMap(VmRequestDetail::getRequestId, Function.identity()));
        Map<Long, LlmKeyRequestDetail> llmKeyDetails = llmKeyDetailRepository
                .findByRequestIdIn(idsOfType(requests, ResourceType.LLM_API_KEY)).stream()
                .collect(Collectors.toMap(LlmKeyRequestDetail::getRequestId, Function.identity()));

        // The per-type spec reports each catalog reference by public id AND by
        // name, so the rows behind them are batched here beside the display-name
        // joins — the same rows either way, kept whole rather than reduced to an
        // id, because the name has to come from somewhere and this is already
        // the query that has it.
        Map<Long, OsImage> images = byId(osImageRepository.findAllById(ids(Stream.concat(
                vmDetails.values().stream().map(VmRequestDetail::getImageId),
                vmDetails.values().stream().map(VmRequestDetail::getGrantedImageId)))),
                OsImage::getId);
        Map<Long, VmFlavor> flavors = byId(vmFlavorRepository.findAllById(ids(
                vmDetails.values().stream().map(VmRequestDetail::getFlavorId))), VmFlavor::getId);
        Map<Long, Node> nodes = byId(nodeRepository.findAllById(ids(
                vmDetails.values().stream().map(VmRequestDetail::getNodeId))), Node::getId);
        // 고른 기간의 이름. 종료일 자체는 신청 행에 복사돼 있으므로 이 조회가 비어도
        // 기간은 그대로 읽히고, 이름 자리만 비게 된다.
        Map<Long, RequestPeriodPreset> periods = byId(periodPresetRepository.findAllById(ids(
                requests.stream().map(Request::getReqPeriodPresetId))),
                RequestPeriodPreset::getId);

        List<RequestDetailResponse> details = new ArrayList<>(requests.size());
        for (Request request : requests) {
            RequestReview review = reviews.get(request.getId());
            Workspace workspace = workspaces.get(request.getWorkspaceId());
            Org org = orgs.get(request.getOrgId());
            User requester = users.get(request.getRequesterId());
            VmRequestDetail vmDetail = vmDetails.get(request.getId());
            LlmKeyRequestDetail llmKeyDetail = llmKeyDetails.get(request.getId());
            details.add(new RequestDetailResponse(
                    request.getPublicId(),
                    request.getResourceType(),
                    workspace != null ? workspace.getPublicId() : null,
                    workspace != null ? workspace.getName() : null,
                    org != null ? org.getPublicId() : null, org != null ? org.getName() : null,
                    requester != null ? requester.getPublicId() : null,
                    requester != null ? requester.getName() : "탈퇴 회원",
                    request.getPurpose(), request.getExtraNote(),
                    request.getReqEndDate(),
                    periodName(periods, request.getReqPeriodPresetId()),
                    request.getDisplayName(),
                    request.getStatus(),
                    review != null
                            ? RequestReviewResponse.from(review, users.get(review.getReviewerId()))
                            : null,
                    vmDetail != null ? VmRequestSpecResponse.from(vmDetail,
                            images.get(vmDetail.getImageId()),
                            flavors.get(vmDetail.getFlavorId()),
                            images.get(vmDetail.getGrantedImageId()),
                            nodes.get(vmDetail.getNodeId())) : null,
                    llmKeyDetail != null ? LlmKeyRequestSpecResponse.from(llmKeyDetail,
                            CreditModelPatterns.fromJson(objectMapper,
                                    llmKeyDetail.getGrantedCreditAllowedModels()),
                            CreditModelPatterns.fromJson(objectMapper,
                                    llmKeyDetail.getGrantedCreditDeniedModels())) : null,
                    request.getCreatedAt(), request.getUpdatedAt()));
        }
        return details;
    }

    private static List<Long> idsOfType(List<Request> requests, ResourceType type) {
        return requests.stream()
                .filter(request -> request.getResourceType() == type)
                .map(Request::getId)
                .toList();
    }

    private static Set<Long> ids(Stream<Long> stream) {
        return stream.filter(java.util.Objects::nonNull).collect(Collectors.toSet());
    }

    private static <T> Map<Long, T> byId(List<T> entities, Function<T, Long> idOf) {
        return entities.stream().collect(Collectors.toMap(idOf, Function.identity()));
    }
}
