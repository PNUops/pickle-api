package kr.ac.pusan.pickle.request.dto;

import java.time.Instant;
import java.time.LocalDate;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.request.ReviewDecision;
import kr.ac.pusan.pickle.request.RequestReview;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code RequestReview} (decision embedded in the request
 * detail). What was granted of the resource itself is reported under the
 * request's per-type member, since only the period is common to every type.
 */
public record RequestReviewResponse(
        Long reviewerId,
        String reviewerName,
        ReviewDecision decision,
        @Nullable String comment,
        @Nullable LocalDate grantedStartDate,
        @Nullable LocalDate grantedEndDate,
        Instant decidedAt) {

    public static RequestReviewResponse from(RequestReview review, User reviewer) {
        return new RequestReviewResponse(review.getReviewerId(),
                reviewer != null ? reviewer.getName() : "탈퇴 회원",
                review.getDecision(), review.getComment(),
                review.getGrantedStartDate(), review.getGrantedEndDate(), review.getCreatedAt());
    }
}
