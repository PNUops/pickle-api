package kr.ac.pusan.pickle.group;

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

@Entity
@Table(name = "groups")
public class Group {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "group_kind")
    private GroupKind kind;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Soft-delete stamp (M6). A non-null value hides the group everywhere. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Who deleted the group: the OWNER (deleteGroup) or the withdrawing user. */
    @Column(name = "deleted_by")
    private Long deletedBy;

    protected Group() {
    }

    public Group(GroupKind kind, String name, String slug, String description) {
        this.kind = kind;
        this.name = name;
        this.slug = slug;
        this.description = description;
    }

    public Long getId() {
        return id;
    }

    public GroupKind getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
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

    /** Soft-deletes the group (row kept for VM/audit history). Idempotent-safe caller-guarded. */
    public void softDelete(long deletedBy, Instant when) {
        this.deletedAt = when;
        this.deletedBy = deletedBy;
    }
}
