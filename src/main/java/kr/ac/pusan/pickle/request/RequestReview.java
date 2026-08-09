package kr.ac.pusan.pickle.request;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * Approve/reject decision (request_reviews). One row per request; the granted
 * period is null on REJECT. What was granted of the resource itself lives on
 * the request's per-type detail row, since only the period is common to every
 * resource type.
 */
@Entity
@Table(name = "request_reviews")
public class RequestReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, unique = true)
    private Long requestId;

    @Column(name = "reviewer_id", nullable = false)
    private Long reviewerId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "review_decision")
    private ReviewDecision decision;

    private String comment;





    @Column(name = "granted_start_date")
    private LocalDate grantedStartDate;

    @Column(name = "granted_end_date")
    private LocalDate grantedEndDate;


    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RequestReview() {
    }

    /** Rejection: decision row with the (mandatory) reviewer comment only. */
    public static RequestReview reject(Long requestId, Long reviewerId, String comment) {
        RequestReview review = new RequestReview();
        review.requestId = requestId;
        review.reviewerId = reviewerId;
        review.decision = ReviewDecision.REJECT;
        review.comment = comment;
        return review;
    }

    /** Approval: decision row carrying the granted period. */
    public static RequestReview approve(Long requestId, Long reviewerId, String comment,
            LocalDate grantedStartDate, LocalDate grantedEndDate) {
        RequestReview review = new RequestReview();
        review.requestId = requestId;
        review.reviewerId = reviewerId;
        review.decision = ReviewDecision.APPROVE;
        review.comment = comment;
        review.grantedStartDate = grantedStartDate;
        review.grantedEndDate = grantedEndDate;
        return review;
    }

    public Long getId() {
        return id;
    }

    public Long getRequestId() {
        return requestId;
    }

    public Long getReviewerId() {
        return reviewerId;
    }

    public ReviewDecision getDecision() {
        return decision;
    }

    public String getComment() {
        return comment;
    }

    public LocalDate getGrantedStartDate() {
        return grantedStartDate;
    }

    public LocalDate getGrantedEndDate() {
        return grantedEndDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
