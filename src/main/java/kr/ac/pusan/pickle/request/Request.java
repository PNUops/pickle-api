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
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * A request for a resource of some type. Rows keep their final status forever.
 *
 * <p>What is asked for lives in a per-type detail row keyed by this id: who is
 * asking, on whose behalf, why and for how long reads the same whatever the
 * resource is, and only the specification does not.
 */
@Entity
@Table(name = "requests")
public class Request {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The identifier this row wears outside the API boundary. Internal joins,
     * sorts and foreign keys keep using {@link #id}.
     */
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "public_id", nullable = false, updatable = false, unique = true)
    private UUID publicId = UUID.randomUUID();

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "resource_type", nullable = false, columnDefinition = "resource_type")
    private ResourceType resourceType;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "requester_id", nullable = false)
    private Long requesterId;

    @Column(nullable = false)
    private String purpose;

    @Column(name = "course_or_project")
    private String courseOrProject;

    @Column(name = "extra_note")
    private String extraNote;

    /**
     * The period this request asks for. Null is the indefinite period, which a
     * requester can only reach by picking a catalogue row that carries no end
     * date -- the form itself has no way to leave this blank.
     */
    @Column(name = "req_end_date")
    private LocalDate reqEndDate;

    /**
     * Which catalogue row the end date was copied from, or null when it was
     * typed. The date is copied rather than joined so that an operator
     * correcting next term's date does not move a request already submitted.
     */
    @Column(name = "req_period_preset_id")
    private Long reqPeriodPresetId;

    /**
     * Requester-chosen name for the resource; seeds its settings at approval.
     * Required on every request, whatever the resource type — a reference to
     * this request is reported by name as well as by public id, and a UUID on
     * its own cannot be read, remembered or spoken.
     */
    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "request_status")
    private RequestStatus status = RequestStatus.SUBMITTED;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Request() {
    }

    public Request(ResourceType resourceType, Long workspaceId, Long orgId, Long requesterId,
            String purpose, String courseOrProject, String extraNote,
            LocalDate reqEndDate, Long reqPeriodPresetId, String displayName) {
        this.resourceType = resourceType;
        this.workspaceId = workspaceId;
        this.orgId = orgId;
        this.requesterId = requesterId;
        this.purpose = purpose;
        this.courseOrProject = courseOrProject;
        this.extraNote = extraNote;
        this.reqEndDate = reqEndDate;
        this.reqPeriodPresetId = reqPeriodPresetId;
        this.displayName = displayName;
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public ResourceType getResourceType() {
        return resourceType;
    }

    public Long getWorkspaceId() {
        return workspaceId;
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

    public String getExtraNote() {
        return extraNote;
    }

    public Long getReqPeriodPresetId() {
        return reqPeriodPresetId;
    }

    public LocalDate getReqEndDate() {
        return reqEndDate;
    }

    public String getDisplayName() {
        return displayName;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public void setStatus(RequestStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
