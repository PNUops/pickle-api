package kr.ac.pusan.pickle.access;

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
 * One entry in a resource's access list: this person (or this whole owning
 * workspace) may act on this resource at this rung.
 *
 * <p>There is no foreign key to the resource, because the row's target depends
 * on {@link #resourceType}. What keeps the list honest is the lifecycle
 * cleanup: losing workspace membership drops that person's grants. Destroying a VM
 * does not — the row is kept so its history stays readable, and this list is
 * what decides who may still read it. A resource type whose rows really do go
 * away has to clear its grants when they do.
 */
@Entity
@Table(name = "resource_access_grants")
public class ResourceAccessGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "resource_type", nullable = false, columnDefinition = "resource_type")
    private ResourceType resourceType;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "grantee_type", nullable = false, columnDefinition = "access_grantee_type")
    private AccessGranteeType granteeType;

    /** The person a USER grant names; null on a workspace-wide grant. */
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "resource_role")
    private ResourceRole role;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ResourceAccessGrant() {
    }

    private ResourceAccessGrant(ResourceType resourceType, long resourceId,
            AccessGranteeType granteeType, Long userId, ResourceRole role) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.granteeType = granteeType;
        this.userId = userId;
        this.role = role;
    }

    public static ResourceAccessGrant forUser(ResourceType type, long resourceId, long userId,
            ResourceRole role) {
        return new ResourceAccessGrant(type, resourceId, AccessGranteeType.USER, userId, role);
    }

    /** Everyone in the owning workspace, at a rung the schema caps below EDITOR. */
    public static ResourceAccessGrant forOwningWorkspace(ResourceType type, long resourceId,
            ResourceRole role) {
        return new ResourceAccessGrant(type, resourceId, AccessGranteeType.WORKSPACE, null, role);
    }

    public Long getId() {
        return id;
    }

    public ResourceType getResourceType() {
        return resourceType;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public AccessGranteeType getGranteeType() {
        return granteeType;
    }

    public Long getUserId() {
        return userId;
    }

    public ResourceRole getRole() {
        return role;
    }

    public void setRole(ResourceRole role) {
        this.role = role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
