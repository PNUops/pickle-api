package kr.ac.pusan.pickle.access;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResourceAccessGrantRepository extends JpaRepository<ResourceAccessGrant, Long> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<ResourceAccessGrant> findByPublicId(UUID publicId);

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
     * Drops one person's grants on a named set of resources of one type — what
     * losing workspace membership means for the access lists.
     *
     * <p>The caller supplies the ids, which is what keeps this free of any one
     * resource type: the query named {@code 'VM'} and the {@code vms} table
     * directly until adapters could answer "what does this workspace own".
     * Both enums travel as parameters rather than as literals, for the reason
     * spelled out above {@code findBy...RoleIn}.
     */
    @Modifying
    @Query("""
            delete from ResourceAccessGrant g
             where g.granteeType = :granteeType and g.userId = :userId
               and g.resourceType = :resourceType and g.resourceId in :resourceIds
            """)
    int deleteUserGrantsOnResources(@Param("granteeType") AccessGranteeType granteeType,
            @Param("userId") Long userId, @Param("resourceType") ResourceType resourceType,
            @Param("resourceIds") Collection<Long> resourceIds);
}
