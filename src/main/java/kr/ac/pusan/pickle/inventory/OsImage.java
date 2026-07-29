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

/** OS catalog entry (which Proxmox image to clone + its disk floor); spec
 * presets live in {@link VmFlavor} since the axis split (contract v0.23.0). */
@Entity
@Table(name = "os_images")
public class OsImage {

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

    protected OsImage() {
    }

    public OsImage(String name, String displayName, int proxmoxVmid, Long nodeId, int version,
            int minDiskGb, TemplateStatus status, String notes) {
        this.name = name;
        this.displayName = displayName;
        this.proxmoxVmid = proxmoxVmid;
        this.nodeId = nodeId;
        this.version = version;
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

    public int getMinDiskGb() {
        return minDiskGb;
    }

    public TemplateStatus getStatus() {
        return status;
    }

    /** Admin status toggle (contract v0.21.0) — retiring old revisions needs a write path. */
    public void setStatus(TemplateStatus status) {
        this.status = status;
    }

    public String getNotes() {
        return notes;
    }
}
