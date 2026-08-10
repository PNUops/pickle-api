package kr.ac.pusan.pickle.provisioning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persisted DB↔Proxmox drift observation (V17). Rows are
 * written exclusively by {@link DriftReconciler} through the native upsert on
 * {@link DriftFindingRepository} — one OPEN row per (kind, dedup_key), bumped
 * on re-observation, auto-resolved when the condition disappears, or manually
 * resolved by a SYS_ADMIN (CAS, {@code resolved_by} set).
 */
@Entity
@Table(name = "drift_findings")
public class DriftFinding {

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
    @Column(nullable = false, columnDefinition = "drift_finding_kind")
    private DriftFindingKind kind;

    @Column(name = "vm_id")
    private Long vmId;

    @Column(name = "proxmox_vmid")
    private Integer proxmoxVmid;

    @Column(name = "node_name")
    private String nodeName;

    /** Korean one-liner shown in the admin list. */
    @Column(nullable = false)
    private String summary;

    /** Optional structured detail (e.g. expected/actual spec values). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String detail;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "drift_finding_status")
    private DriftFindingStatus status = DriftFindingStatus.OPEN;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** Null on a RESOLVED row = auto-resolved by the reconciler. */
    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolution_note")
    private String resolutionNote;

    /** {@code vm:<id>} for kinds ①/③, {@code vmid:<n>} for ②. */
    @Column(name = "dedup_key", nullable = false)
    private String dedupKey;

    protected DriftFinding() {
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public DriftFindingKind getKind() {
        return kind;
    }

    public Long getVmId() {
        return vmId;
    }

    public Integer getProxmoxVmid() {
        return proxmoxVmid;
    }

    public String getNodeName() {
        return nodeName;
    }

    public String getSummary() {
        return summary;
    }

    public String getDetail() {
        return detail;
    }

    public DriftFindingStatus getStatus() {
        return status;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Long getResolvedBy() {
        return resolvedBy;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public String getDedupKey() {
        return dedupKey;
    }
}
