package kr.ac.pusan.pickle.inventory;

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

/** VM template preset offered in the request wizard. */
@Entity
@Table(name = "vm_templates")
public class VmTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "proxmox_vmid", nullable = false)
    private int proxmoxVmid;

    @Column(name = "node_id", nullable = false)
    private Long nodeId;

    @Column(nullable = false)
    private int version = 1;

    @Column(name = "default_vcpu", nullable = false)
    private int defaultVcpu;

    @Column(name = "default_memory_mb", nullable = false)
    private int defaultMemoryMb;

    @Column(name = "default_disk_gb", nullable = false)
    private int defaultDiskGb;

    @Column(name = "min_disk_gb", nullable = false)
    private int minDiskGb;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "template_status")
    private TemplateStatus status = TemplateStatus.ACTIVE;

    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VmTemplate() {
    }

    public VmTemplate(String name, String displayName, int proxmoxVmid, Long nodeId, int version,
            int defaultVcpu, int defaultMemoryMb, int defaultDiskGb, int minDiskGb,
            TemplateStatus status, String notes) {
        this.name = name;
        this.displayName = displayName;
        this.proxmoxVmid = proxmoxVmid;
        this.nodeId = nodeId;
        this.version = version;
        this.defaultVcpu = defaultVcpu;
        this.defaultMemoryMb = defaultMemoryMb;
        this.defaultDiskGb = defaultDiskGb;
        this.minDiskGb = minDiskGb;
        this.status = status;
        this.notes = notes;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getProxmoxVmid() {
        return proxmoxVmid;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public int getVersion() {
        return version;
    }

    public int getDefaultVcpu() {
        return defaultVcpu;
    }

    public int getDefaultMemoryMb() {
        return defaultMemoryMb;
    }

    public int getDefaultDiskGb() {
        return defaultDiskGb;
    }

    public int getMinDiskGb() {
        return minDiskGb;
    }

    public TemplateStatus getStatus() {
        return status;
    }

    public String getNotes() {
        return notes;
    }
}
