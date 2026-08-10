package kr.ac.pusan.pickle.workspace;

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

@Entity
@Table(name = "workspaces")
public class Workspace {

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
    @Column(nullable = false, columnDefinition = "workspace_kind")
    private WorkspaceKind kind;

    @Column(nullable = false)
    private String name;

    private String description;

    /** Soft-delete stamp. A non-null value hides the workspace everywhere. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Who deleted the workspace: the OWNER (deleteWorkspace) or the withdrawing user. */
    @Column(name = "deleted_by")
    private Long deletedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Workspace() {
    }

    public Workspace(WorkspaceKind kind, String name, String description) {
        this.kind = kind;
        this.name = name;
        this.description = description;
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public WorkspaceKind getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Long getDeletedBy() {
        return deletedBy;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * Soft-delete stamp: workspace delete and PERSONAL-workspace cleanup on
     * withdrawal — the row is kept for VM/audit history.
     */
    public void softDelete(Long actorId, Instant when) {
        this.deletedAt = when;
        this.deletedBy = actorId;
    }
}
