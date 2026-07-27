package kr.ac.pusan.pickle.vmrequest;

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
 * Approve/reject decision with the granted values (vm_request_reviews).
 * One row per request; granted columns are null on REJECT.
 */
@Entity
@Table(name = "vm_request_reviews")
public class VmRequestReview {

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

    @Column(name = "granted_vcpu")
    private Integer grantedVcpu;

    @Column(name = "granted_memory_mb")
    private Integer grantedMemoryMb;

    @Column(name = "granted_disk_gb")
    private Integer grantedDiskGb;

    @Column(name = "granted_template_id")
    private Long grantedTemplateId;

    @Column(name = "granted_start_date")
    private LocalDate grantedStartDate;

    @Column(name = "granted_end_date")
    private LocalDate grantedEndDate;

    /** Reviewer-forced placement node; null = auto placement. */
    @Column(name = "node_id")
    private Long nodeId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VmRequestReview() {
    }

    /** Rejection: decision row with the (mandatory) reviewer comment only. */
    public static VmRequestReview reject(Long requestId, Long reviewerId, String comment) {
        VmRequestReview review = new VmRequestReview();
        review.requestId = requestId;
        review.reviewerId = reviewerId;
        review.decision = ReviewDecision.REJECT;
        review.comment = comment;
        return review;
    }

    /** Approval: decision row carrying the granted spec. */
    public static VmRequestReview approve(Long requestId, Long reviewerId, String comment,
            int grantedVcpu, int grantedMemoryMb, int grantedDiskGb, Long grantedTemplateId,
            LocalDate grantedStartDate, LocalDate grantedEndDate, Long nodeId) {
        VmRequestReview review = new VmRequestReview();
        review.requestId = requestId;
        review.reviewerId = reviewerId;
        review.decision = ReviewDecision.APPROVE;
        review.comment = comment;
        review.grantedVcpu = grantedVcpu;
        review.grantedMemoryMb = grantedMemoryMb;
        review.grantedDiskGb = grantedDiskGb;
        review.grantedTemplateId = grantedTemplateId;
        review.grantedStartDate = grantedStartDate;
        review.grantedEndDate = grantedEndDate;
        review.nodeId = nodeId;
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

    public Integer getGrantedVcpu() {
        return grantedVcpu;
    }

    public Integer getGrantedMemoryMb() {
        return grantedMemoryMb;
    }

    public Integer getGrantedDiskGb() {
        return grantedDiskGb;
    }

    public Long getGrantedTemplateId() {
        return grantedTemplateId;
    }

    public LocalDate getGrantedStartDate() {
        return grantedStartDate;
    }

    public LocalDate getGrantedEndDate() {
        return grantedEndDate;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
