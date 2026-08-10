package kr.ac.pusan.pickle.request.dto;

import java.time.Instant;
import java.time.LocalDate;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
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
        @Schema(description = "결재자. 계정 행이 사라진 경우에만 null입니다.")
        @Nullable UUID reviewerId,
        String reviewerName,
        ReviewDecision decision,
        @Nullable String comment,
        @Nullable LocalDate grantedStartDate,
        @Nullable LocalDate grantedEndDate,
        Instant decidedAt) {

    public static RequestReviewResponse from(RequestReview review, User reviewer) {
        return new RequestReviewResponse(reviewer != null ? reviewer.getPublicId() : null,
                reviewer != null ? reviewer.getName() : "탈퇴 회원",
                review.getDecision(), review.getComment(),
                review.getGrantedStartDate(), review.getGrantedEndDate(), review.getCreatedAt());
    }
}
