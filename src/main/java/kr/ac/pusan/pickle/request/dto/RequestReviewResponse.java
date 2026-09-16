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
        @Schema(description = "결재한 사람. 자동 승인이거나 계정 행이 사라진 경우 null입니다.")
        @Nullable UUID reviewerId,
        @Schema(description = "결재자 이름. 자동 승인이면 「자동 승인」, 계정 행이 사라졌으면 "
                + "「탈퇴 회원」입니다. 두 경우 모두 reviewerId가 null이므로 이 값으로 가릅니다.")
        String reviewerName,
        ReviewDecision decision,
        @Nullable String comment,
        @Nullable LocalDate grantedStartDate,
        @Nullable LocalDate grantedEndDate,
        Instant decidedAt) {

    public static RequestReviewResponse from(RequestReview review, User reviewer) {
        // Three states, not two. A null reviewer on the row means the platform
        // approved this itself; a reviewer id that no longer resolves means a
        // person did and their account is gone. Collapsing them would report an
        // automatic approval as the work of a withdrawn member.
        String name;
        if (review.getReviewerId() == null) {
            name = "자동 승인";
        } else {
            name = reviewer != null ? reviewer.getName() : "탈퇴 회원";
        }
        return new RequestReviewResponse(reviewer != null ? reviewer.getPublicId() : null,
                name, review.getDecision(), review.getComment(),
                review.getGrantedStartDate(), review.getGrantedEndDate(), review.getCreatedAt());
    }
}
