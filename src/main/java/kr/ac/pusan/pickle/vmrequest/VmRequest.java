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

/** User VM request (vm_requests). Rows keep their final status forever. */
@Entity
@Table(name = "vm_requests")
public class VmRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "requester_id", nullable = false)
    private Long requesterId;

    @Column(nullable = false)
    private String purpose;

    @Column(name = "course_or_project")
    private String courseOrProject;

    @Column(name = "spec_reason")
    private String specReason;

    @Column(name = "extra_note")
    private String extraNote;

    @Column(name = "image_id", nullable = false)
    private Long imageId;

    /** Chosen spec preset (V58 axis split) — provenance + specReason baseline. */
    @Column(name = "flavor_id")
    private Long flavorId;

    @Column(name = "req_vcpu", nullable = false)
    private int reqVcpu;

    @Column(name = "req_memory_mb", nullable = false)
    private int reqMemoryMb;

    @Column(name = "req_disk_gb", nullable = false)
    private int reqDiskGb;

    @Column(name = "req_start_date")
    private LocalDate reqStartDate;

    @Column(name = "req_end_date")
    private LocalDate reqEndDate;

    /** Requester-desired platform subdomain; the publish-time default. */
    @Column(name = "desired_subdomain")
    private String desiredSubdomain;

    @Column(name = "root_domain")
    private String rootDomain;

    /** Requester-chosen VM display name; seeds vm_settings at approval. */
    @Column(name = "display_name")
    private String displayName;

    /** Requester-desired hostname/slug (v0.12.0); null = auto-generate at approval. */
    @Column(name = "desired_slug")
    private String desiredSlug;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "vm_request_status")
    private VmRequestStatus status = VmRequestStatus.SUBMITTED;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VmRequest() {
    }

    public VmRequest(Long groupId, Long orgId, Long requesterId, Long imageId, Long flavorId,
            String purpose, String courseOrProject, String specReason, String extraNote,
            int reqVcpu, int reqMemoryMb, int reqDiskGb, LocalDate reqStartDate, LocalDate reqEndDate,
            String desiredSubdomain, String rootDomain, String displayName, String desiredSlug) {
        this.groupId = groupId;
        this.orgId = orgId;
        this.requesterId = requesterId;
        this.imageId = imageId;
        this.flavorId = flavorId;
        this.purpose = purpose;
        this.courseOrProject = courseOrProject;
        this.specReason = specReason;
        this.extraNote = extraNote;
        this.reqVcpu = reqVcpu;
        this.reqMemoryMb = reqMemoryMb;
        this.reqDiskGb = reqDiskGb;
        this.reqStartDate = reqStartDate;
        this.reqEndDate = reqEndDate;
        this.desiredSubdomain = desiredSubdomain;
        this.rootDomain = rootDomain;
        this.displayName = displayName;
        this.desiredSlug = desiredSlug;
    }

    public Long getId() {
        return id;
    }

    public Long getGroupId() {
        return groupId;
    }

    public Long getOrgId() {
        return orgId;
    }

    public Long getRequesterId() {
        return requesterId;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getCourseOrProject() {
        return courseOrProject;
    }

    public String getSpecReason() {
        return specReason;
    }

    public String getExtraNote() {
        return extraNote;
    }

    public Long getImageId() {
        return imageId;
    }

    public Long getFlavorId() {
        return flavorId;
    }

    public int getReqVcpu() {
        return reqVcpu;
    }

    public int getReqMemoryMb() {
        return reqMemoryMb;
    }

    public int getReqDiskGb() {
        return reqDiskGb;
    }

    public LocalDate getReqStartDate() {
        return reqStartDate;
    }

    public LocalDate getReqEndDate() {
        return reqEndDate;
    }

    public String getDesiredSubdomain() {
        return desiredSubdomain;
    }

    public String getRootDomain() {
        return rootDomain;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDesiredSlug() {
        return desiredSlug;
    }

    public VmRequestStatus getStatus() {
        return status;
    }

    public void setStatus(VmRequestStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
