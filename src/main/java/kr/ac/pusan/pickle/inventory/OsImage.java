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

    /** Distribution short id ({@code ubuntu}, {@code debian}, {@code rocky}) — free
     * text so a new OS stays a catalog row rather than a schema change (V63). */
    @Column(name = "os_family", nullable = false)
    private String osFamily;

    /** Release string as the distribution writes it ({@code 24.04}, {@code 13},
     * {@code 10}) — a label, not a sort key (V63). */
    @Column(name = "os_version", nullable = false)
    private String osVersion;

    /** Guest admin account this image ships; becomes the VM's login name and the
     * cloud-init {@code ciuser} at provisioning (V63). */
    @Column(name = "ssh_username", nullable = false)
    private String sshUsername;

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

    public OsImage(String name, String displayName, String osFamily, String osVersion,
            String sshUsername, int proxmoxVmid, Long nodeId, int version, int minDiskGb,
            TemplateStatus status, String notes) {
        this.name = name;
        this.displayName = displayName;
        this.osFamily = osFamily;
        this.osVersion = osVersion;
        this.sshUsername = sshUsername;
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

    public String getOsFamily() {
        return osFamily;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public String getSshUsername() {
        return sshUsername;
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
