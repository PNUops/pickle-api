package kr.ac.pusan.pickle.vmrequest;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VmRequestRepository extends JpaRepository<VmRequest, Long> {

    /**
     * Locked lookup for decision/cancel mutations: concurrent decisions on the
     * same request serialize, so exactly one wins and the rest see 409.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from VmRequest r where r.id = :id")
    Optional<VmRequest> findWithLockById(@Param("id") Long id);

    /** User list visibility: own requests + requests of groups I belong to. */
    @Query("select r from VmRequest r where r.requesterId = :userId or r.groupId in :groupIds")
    Page<VmRequest> findVisible(@Param("userId") Long userId,
            @Param("groupIds") Collection<Long> groupIds, Pageable pageable);

    @Query("""
            select r from VmRequest r
             where (r.requesterId = :userId or r.groupId in :groupIds) and r.status = :status
            """)
    Page<VmRequest> findVisibleByStatus(@Param("userId") Long userId,
            @Param("groupIds") Collection<Long> groupIds,
            @Param("status") VmRequestStatus status, Pageable pageable);

    Page<VmRequest> findByGroupId(Long groupId, Pageable pageable);

    Page<VmRequest> findByGroupIdAndStatus(Long groupId, VmRequestStatus status, Pageable pageable);

    Page<VmRequest> findByStatus(VmRequestStatus status, Pageable pageable);

    Page<VmRequest> findByOrgId(Long orgId, Pageable pageable);

    Page<VmRequest> findByOrgIdAndStatus(Long orgId, VmRequestStatus status, Pageable pageable);

    long countByRequesterIdAndStatus(Long requesterId, VmRequestStatus status);

    /**
     * Duplicate-subdomain guard (contract: 예약어·중복은 서버에서 검증):
     * a (subdomain, rootDomain) pair is taken while another request holds it
     * in a non-terminal state (SUBMITTED/APPROVED).
     */
    boolean existsByDesiredSubdomainAndRootDomainAndStatusIn(String desiredSubdomain, String rootDomain,
            Collection<VmRequestStatus> statuses);

    /** Approval-context history: prior requests by the same user or group. */
    @Query("""
            select r from VmRequest r
             where (r.requesterId = :userId or r.groupId = :groupId) and r.id <> :excludeId
            """)
    List<VmRequest> findHistory(@Param("userId") Long userId, @Param("groupId") Long groupId,
            @Param("excludeId") Long excludeId, Pageable pageable);
}
