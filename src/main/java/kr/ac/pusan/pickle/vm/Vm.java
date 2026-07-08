package kr.ac.pusan.pickle.vm;

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
 * Virtual machine (docs/plan/02 vms). Created on approval with the granted
 * spec; {@code proxmox_vmid} and {@code ip_allocation_id} are filled by the
 * M3 pipeline, which also manages the initial-credential and scheduled-delete
 * columns (V6). Rows are never physically deleted.
 */
@Entity
@Table(name = "vms")
public class Vm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "proxmox_vmid", unique = true)
    private Integer proxmoxVmid;

    @Column(name = "node_id", nullable = false)
    private Long nodeId;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String hostname;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(nullable = false)
    private int vcpu;

    @Column(name = "memory_mb", nullable = false)
    private int memoryMb;

    @Column(name = "disk_gb", nullable = false)
    private int diskGb;

    @Column(name = "ip_allocation_id")
    private Long ipAllocationId;

    /** Fixed {@code student} (docs/plan/03 initial credentials). */
    @Column(name = "ssh_username", nullable = false)
    private String sshUsername = "student";

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "vm_status")
    private VmStatus status = VmStatus.CREATING;

    @Column(name = "status_detail")
    private String statusDetail;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    /**
     * Plaintext by design, but only until first view: the one-shot password
     * endpoint returns it once and nulls it (docs/plan/03 initial credentials).
     */
    @Column(name = "initial_password")
    private String initialPassword;

    /** BCrypt hash kept permanently for support verification. */
    @Column(name = "initial_password_hash")
    private String initialPasswordHash;

    @Column(name = "initial_password_viewed_at")
    private Instant initialPasswordViewedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "delete_kind", columnDefinition = "vm_delete_kind")
    private VmDeleteKind deleteKind;

    /** When the deletion sweeper may hard-delete (docs/plan/03 grace/notice). */
    @Column(name = "delete_scheduled_for")
    private Instant deleteScheduledFor;

    @Column(name = "delete_requested_by")
    private Long deleteRequestedBy;

    @Column(name = "delete_reason")
    private String deleteReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Vm() {
    }

    public Vm(Long nodeId, Long groupId, Long orgId, Long requestId, String name, String hostname,
            Long templateId, int vcpu, int memoryMb, int diskGb, LocalDate startDate, LocalDate endDate) {
        this.nodeId = nodeId;
        this.groupId = groupId;
        this.orgId = orgId;
        this.requestId = requestId;
        this.name = name;
        this.hostname = hostname;
        this.templateId = templateId;
        this.vcpu = vcpu;
        this.memoryMb = memoryMb;
        this.diskGb = diskGb;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public Long getId() {
        return id;
    }

    public Integer getProxmoxVmid() {
        return proxmoxVmid;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public Long getGroupId() {
        return groupId;
    }

    public Long getOrgId() {
        return orgId;
    }

    public Long getRequestId() {
        return requestId;
    }

    public String getName() {
        return name;
    }

    public String getHostname() {
        return hostname;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public int getVcpu() {
        return vcpu;
    }

    public int getMemoryMb() {
        return memoryMb;
    }

    public int getDiskGb() {
        return diskGb;
    }

    public Long getIpAllocationId() {
        return ipAllocationId;
    }

    public String getSshUsername() {
        return sshUsername;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public VmStatus getStatus() {
        return status;
    }

    public String getStatusDetail() {
        return statusDetail;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Long getDeletedBy() {
        return deletedBy;
    }

    public String getInitialPassword() {
        return initialPassword;
    }

    public String getInitialPasswordHash() {
        return initialPasswordHash;
    }

    public Instant getInitialPasswordViewedAt() {
        return initialPasswordViewedAt;
    }

    public VmDeleteKind getDeleteKind() {
        return deleteKind;
    }

    public Instant getDeleteScheduledFor() {
        return deleteScheduledFor;
    }

    public Long getDeleteRequestedBy() {
        return deleteRequestedBy;
    }

    public String getDeleteReason() {
        return deleteReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
