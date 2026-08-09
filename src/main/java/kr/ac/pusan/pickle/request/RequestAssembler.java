package kr.ac.pusan.pickle.request;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.request.dto.RequestDetailResponse;
import kr.ac.pusan.pickle.request.dto.RequestReviewResponse;
import kr.ac.pusan.pickle.request.vm.VmRequestDetail;
import kr.ac.pusan.pickle.request.vm.VmRequestDetailRepository;
import kr.ac.pusan.pickle.request.vm.dto.VmRequestSpecResponse;
import org.springframework.stereotype.Component;

/**
 * Builds contract {@code RequestDetail} payloads: resolves workspace/org/user
 * display names and the review (when decided) with batched lookups, so list
 * pages stay free of per-row queries.
 */
@Component
public class RequestAssembler {

    private final RequestReviewRepository reviewRepository;
    private final VmRequestDetailRepository vmDetailRepository;
    private final WorkspaceRepository workspaceRepository;
    private final OrgRepository orgRepository;
    private final UserRepository userRepository;

    public RequestAssembler(RequestReviewRepository reviewRepository,
            VmRequestDetailRepository vmDetailRepository, WorkspaceRepository workspaceRepository,
            OrgRepository orgRepository, UserRepository userRepository) {
        this.reviewRepository = reviewRepository;
        this.vmDetailRepository = vmDetailRepository;
        this.workspaceRepository = workspaceRepository;
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
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

        List<RequestDetailResponse> details = new ArrayList<>(requests.size());
        for (Request request : requests) {
            RequestReview review = reviews.get(request.getId());
            Workspace workspace = workspaces.get(request.getWorkspaceId());
            Org org = orgs.get(request.getOrgId());
            User requester = users.get(request.getRequesterId());
            VmRequestDetail vmDetail = vmDetails.get(request.getId());
            details.add(new RequestDetailResponse(
                    request.getId(),
                    request.getResourceType(),
                    request.getWorkspaceId(), workspace != null ? workspace.getName() : null,
                    request.getOrgId(), org != null ? org.getName() : null,
                    request.getRequesterId(), requester != null ? requester.getName() : "탈퇴 회원",
                    request.getPurpose(), request.getCourseOrProject(), request.getExtraNote(),
                    request.getReqStartDate(), request.getReqEndDate(), request.getDisplayName(),
                    request.getStatus(),
                    review != null
                            ? RequestReviewResponse.from(review, users.get(review.getReviewerId()))
                            : null,
                    vmDetail != null ? VmRequestSpecResponse.from(vmDetail) : null,
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
        return stream.collect(Collectors.toSet());
    }

    private static <T> Map<Long, T> byId(List<T> entities, Function<T, Long> idOf) {
        return entities.stream().collect(Collectors.toMap(idOf, Function.identity()));
    }
}
