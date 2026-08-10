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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * User-visible provisioning/deletion/reinstall task. All state
 * transitions go through the CAS JPQL methods on
 * {@link ProvisioningTaskRepository} so crashed or duplicated JobRunr job runs
 * are idempotent; the partial unique index on (vm_id, kind) blocks a second
 * live task for the same VM and kind at insert time.
 */
@Entity
@Table(name = "provisioning_tasks")
public class ProvisioningTask {

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

    @Column(name = "vm_id", nullable = false)
    private Long vmId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "provisioning_task_kind")
    private ProvisioningTaskKind kind;

    /** Index into the pipeline's ordered step list. */
    @Column(name = "current_step", nullable = false)
    private int currentStep;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "provisioning_task_status")
    private ProvisioningTaskStatus status = ProvisioningTaskStatus.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "jobrunr_job_id")
    private String jobrunrJobId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProvisioningTask() {
    }

    public ProvisioningTask(Long vmId, ProvisioningTaskKind kind) {
        this.vmId = vmId;
        this.kind = kind;
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Long getVmId() {
        return vmId;
    }

    public ProvisioningTaskKind getKind() {
        return kind;
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public ProvisioningTaskStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public String getJobrunrJobId() {
        return jobrunrJobId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
