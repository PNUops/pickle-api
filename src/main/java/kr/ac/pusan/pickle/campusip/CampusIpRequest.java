package kr.ac.pusan.pickle.campusip;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * A 교내 IP allocation request (campus_ip_requests). Created self-service by
 * a manager-tier member of the VM's workspace, processed by SYS_ADMIN:
 * REQUESTED → APPROVED|REJECTED, APPROVED → GRANTED|REJECTED (granting
 * records the assigned address), GRANTED → REVOKED. One live request per VM.
 */
@Entity
@Table(name = "campus_ip_requests")
public class CampusIpRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vm_id", nullable = false)
    private Long vmId;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(nullable = false)
    private String purpose;

    /** JSON array of the port numbers to open (normalized: deduped, sorted). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String ports;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CampusIpRequestStatus status;

    @Column(name = "granted_address")
    private String grantedAddress;

    @Column(name = "admin_note")
    private String adminNote;

    @Column(name = "processed_by")
    private Long processedBy;

    @Column(name = "processed_at")
    private Instant processedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CampusIpRequest() {
    }

    public CampusIpRequest(Long vmId, Long requestedBy, String purpose, String portsJson) {
        this.vmId = vmId;
        this.requestedBy = requestedBy;
        this.purpose = purpose;
        this.ports = portsJson;
        this.status = CampusIpRequestStatus.REQUESTED;
    }

    public Long getId() {
        return id;
    }

    public Long getVmId() {
        return vmId;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getPorts() {
        return ports;
    }

    public CampusIpRequestStatus getStatus() {
        return status;
    }

    public void setStatus(CampusIpRequestStatus status) {
        this.status = status;
    }

    public String getGrantedAddress() {
        return grantedAddress;
    }

    public void setGrantedAddress(String grantedAddress) {
        this.grantedAddress = grantedAddress;
    }

    public String getAdminNote() {
        return adminNote;
    }

    public void setAdminNote(String adminNote) {
        this.adminNote = adminNote;
    }

    public Long getProcessedBy() {
        return processedBy;
    }

    public void setProcessedBy(Long processedBy) {
        this.processedBy = processedBy;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
