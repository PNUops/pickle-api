package kr.ac.pusan.pickle.vmrequest.dto;

import java.time.Instant;
import java.time.LocalDate;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.vmrequest.ReviewDecision;
import kr.ac.pusan.pickle.vmrequest.VmRequestReview;

/** Contract schema {@code VmRequestReview} (decision embedded in the request detail). */
public record VmRequestReviewResponse(
        Long reviewerId,
        String reviewerName,
        ReviewDecision decision,
        String comment,
        Integer grantedVcpu,
        Integer grantedMemoryMb,
        Integer grantedDiskGb,
        Long grantedTemplateId,
        LocalDate grantedStartDate,
        LocalDate grantedEndDate,
        Boolean grantSsh,
        Boolean grantHttp,
        Boolean grantPublic,
        String grantedSubdomain,
        String grantedRootDomain,
        Long nodeId,
        Instant decidedAt) {

    public static VmRequestReviewResponse from(VmRequestReview review, User reviewer) {
        return new VmRequestReviewResponse(review.getReviewerId(),
                reviewer != null ? reviewer.getName() : "탈퇴 회원",
                review.getDecision(), review.getComment(),
                review.getGrantedVcpu(), review.getGrantedMemoryMb(), review.getGrantedDiskGb(),
                review.getGrantedTemplateId(), review.getGrantedStartDate(), review.getGrantedEndDate(),
                review.getGrantSsh(), review.getGrantHttp(), review.getGrantPublic(),
                review.getGrantedSubdomain(), review.getGrantedRootDomain(),
                review.getNodeId(), review.getCreatedAt());
    }
}
