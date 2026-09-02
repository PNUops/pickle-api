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
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * Spec preset offered in the request wizard (vm_flavors, V58) — the second
 * axis of the [OS] x [flavor] choice. Values prefill the request form and set
 * the spec-reason baseline; the granted spec itself stays denormalized on the
 * request/review/vm rows, so editing a flavor only changes future baselines.
 */
@Entity
@Table(name = "vm_flavors")
public class VmFlavor {

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

    @Column(nullable = false)
    private String name;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private int vcpu;

    @Column(name = "memory_mb", nullable = false)
    private int memoryMb;

    @Column(name = "disk_gb", nullable = false)
    private int diskGb;

    private String notes;

    /**
     * 신청 화면에서의 자리. 사양이 크기 사다리에서 모양별로 바뀌면서 수치로는 순서를
     * 유도할 수 없게 됐다. 무엇을 먼저 보여 줄지는 관리자가 정한다.
     */
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "catalog_status")
    private CatalogStatus status = CatalogStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VmFlavor() {
    }

    public VmFlavor(String name, String displayName, int vcpu, int memoryMb, int diskGb,
            CatalogStatus status, String notes, int displayOrder) {
        this.name = name;
        this.displayName = displayName;
        this.vcpu = vcpu;
        this.memoryMb = memoryMb;
        this.diskGb = diskGb;
        this.status = status;
        this.notes = notes;
        this.displayOrder = displayOrder;
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
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

    public String getNotes() {
        return notes;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public CatalogStatus getStatus() {
        return status;
    }

    // Admin management (list/create/edit/status) writes through these — a
    // flavor must never be a DB-only state (write path ships with the field).
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setVcpu(int vcpu) {
        this.vcpu = vcpu;
    }

    public void setMemoryMb(int memoryMb) {
        this.memoryMb = memoryMb;
    }

    public void setDiskGb(int diskGb) {
        this.diskGb = diskGb;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    public void setStatus(CatalogStatus status) {
        this.status = status;
    }
}
