package kr.ac.pusan.pickle.llm.openrouter;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OpenRouterAccountRepository extends JpaRepository<OpenRouterAccount, Long>,
        JpaSpecificationExecutor<OpenRouterAccount> {

    Optional<OpenRouterAccount> findByPublicId(UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from OpenRouterAccount a where a.publicId = :publicId")
    Optional<OpenRouterAccount> findWithLockByPublicId(@Param("publicId") UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<OpenRouterAccount> findByOrgIdAndStatusOrderByNameAsc(
            long orgId, OpenRouterAccountStatus status);

    /**
     * The same listing without the lock, for a judgment that must not hold
     * anything: a preview runs read-only, and a bulk apply judges before it
     * has taken the generation, which is where every account lock must queue.
     */
    @Query("""
            select a from OpenRouterAccount a
             where a.orgId = :orgId and a.status = :status
             order by a.name asc
            """)
    List<OpenRouterAccount> peekByOrgIdAndStatusOrderByNameAsc(@Param("orgId") long orgId,
            @Param("status") OpenRouterAccountStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from OpenRouterAccount a where a.id = :id")
    Optional<OpenRouterAccount> findWithLockById(@Param("id") long id);

    Optional<OpenRouterAccount> findByVendorWorkspaceId(UUID vendorWorkspaceId);

    Optional<OpenRouterAccount> findByOrgIdAndNameIgnoreCase(long orgId, String name);
}
