package kr.ac.pusan.pickle.vmrequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kr.ac.pusan.pickle.group.Group;
import kr.ac.pusan.pickle.group.GroupRepository;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.vmrequest.dto.VmRequestDetailResponse;
import kr.ac.pusan.pickle.vmrequest.dto.VmRequestReviewResponse;
import org.springframework.stereotype.Component;

/**
 * Builds contract {@code VmRequestDetail} payloads: resolves group/org/user
 * display names and the review (when decided) with batched lookups, so list
 * pages stay free of per-row queries.
 */
@Component
public class VmRequestAssembler {

    private final VmRequestReviewRepository reviewRepository;
    private final GroupRepository groupRepository;
    private final OrgRepository orgRepository;
    private final UserRepository userRepository;

    public VmRequestAssembler(VmRequestReviewRepository reviewRepository, GroupRepository groupRepository,
            OrgRepository orgRepository, UserRepository userRepository) {
        this.reviewRepository = reviewRepository;
        this.groupRepository = groupRepository;
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
    }

    public VmRequestDetailResponse toDetail(VmRequest request) {
        return toDetails(List.of(request)).getFirst();
    }

    public List<VmRequestDetailResponse> toDetails(List<VmRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        Map<Long, VmRequestReview> reviews = reviewRepository
                .findByRequestIdIn(requests.stream().map(VmRequest::getId).toList())
                .stream()
                .collect(Collectors.toMap(VmRequestReview::getRequestId, Function.identity()));
        Map<Long, Group> groups = byId(groupRepository
                .findAllById(ids(requests.stream().map(VmRequest::getGroupId))), Group::getId);
        Map<Long, Org> orgs = byId(orgRepository
                .findAllById(ids(requests.stream().map(VmRequest::getOrgId))), Org::getId);
        Map<Long, User> users = byId(userRepository.findAllById(ids(Stream.concat(
                requests.stream().map(VmRequest::getRequesterId),
                reviews.values().stream().map(VmRequestReview::getReviewerId)))), User::getId);

        List<VmRequestDetailResponse> details = new ArrayList<>(requests.size());
        for (VmRequest request : requests) {
            VmRequestReview review = reviews.get(request.getId());
            Group group = groups.get(request.getGroupId());
            Org org = orgs.get(request.getOrgId());
            User requester = users.get(request.getRequesterId());
            details.add(new VmRequestDetailResponse(
                    request.getId(),
                    request.getGroupId(), group != null ? group.getName() : null,
                    request.getOrgId(), org != null ? org.getName() : null,
                    request.getRequesterId(), requester != null ? requester.getName() : "탈퇴 회원",
                    request.getTemplateId(), request.getFlavorId(),
                    request.getPurpose(), request.getCourseOrProject(), request.getSpecReason(),
                    request.getExtraNote(),
                    request.getReqVcpu(), request.getReqMemoryMb(), request.getReqDiskGb(),
                    request.getReqStartDate(), request.getReqEndDate(),
                    request.getDesiredSubdomain(), request.getRootDomain(), request.getDisplayName(),
                    request.getDesiredSlug(),
                    request.getStatus(),
                    review != null
                            ? VmRequestReviewResponse.from(review, users.get(review.getReviewerId()))
                            : null,
                    request.getCreatedAt(), request.getUpdatedAt()));
        }
        return details;
    }

    private static Set<Long> ids(Stream<Long> stream) {
        return stream.collect(Collectors.toSet());
    }

    private static <T> Map<Long, T> byId(List<T> entities, Function<T, Long> idOf) {
        return entities.stream().collect(Collectors.toMap(idOf, Function.identity()));
    }
}
