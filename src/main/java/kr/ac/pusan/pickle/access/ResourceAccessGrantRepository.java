package kr.ac.pusan.pickle.access;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResourceAccessGrantRepository extends JpaRepository<ResourceAccessGrant, Long> {

    /**
     * Every grant on one resource — the two rows the judgment needs (the
     * requester's own and the workspace-wide one) plus the list surfaces' contents.
     */
    List<ResourceAccessGrant> findByResourceTypeAndResourceIdOrderByIdAsc(ResourceType resourceType,
            Long resourceId);

    Optional<ResourceAccessGrant> findByResourceTypeAndResourceIdAndUserId(
            ResourceType resourceType, Long resourceId, Long userId);

    Optional<ResourceAccessGrant> findByResourceTypeAndResourceIdAndGranteeType(
            ResourceType resourceType, Long resourceId, AccessGranteeType granteeType);

    /** The grants one person holds, for "which resources may I reach" listings. */
    List<ResourceAccessGrant> findByResourceTypeAndUserId(ResourceType resourceType, Long userId);

    /** Grants held on a set of resources, for batch list assembly. */
    List<ResourceAccessGrant> findByResourceTypeAndResourceIdIn(ResourceType resourceType,
            List<Long> resourceIds);

    /**
     * The grants at one of {@code roles} on a resource — how a notification
     * finds its audience now that reaching a VM is a grant rather than a rung in
     * its workspace. Callers pass {@code USER} for {@code granteeType}: a workspace-wide
     * grant names nobody, and the workspace's owners are added separately when the
     * message concerns them.
     *
     * <p>Derived rather than written as JPQL on purpose. An enum literal spelled
     * out inside a query renders its cast from the Java class name
     * ({@code 'USER'::AccessGranteeType}), which is not what the column's type is
     * called, so it fails at runtime rather than at build time.
     */
    List<ResourceAccessGrant> findByResourceTypeAndResourceIdAndGranteeTypeAndRoleIn(
            ResourceType resourceType, Long resourceId, AccessGranteeType granteeType,
            Collection<ResourceRole> roles);

    void deleteByResourceTypeAndResourceId(ResourceType resourceType, Long resourceId);

    /** Withdrawal takes every grant the account held, on every resource. */
    void deleteByUserId(Long userId);

    /**
     * Drops one person's grants on every resource a workspace owns — what losing
     * workspace membership means for the access lists.
     */
    @Modifying
    @Query(value = """
            delete from resource_access_grants g
             where g.grantee_type = 'USER' and g.user_id = :userId
               and g.resource_type = 'VM'
               and g.resource_id in (select v.id from vms v where v.workspace_id = :workspaceId)
            """, nativeQuery = true)
    int deleteUserGrantsInWorkspace(@Param("workspaceId") Long workspaceId, @Param("userId") Long userId);
}
