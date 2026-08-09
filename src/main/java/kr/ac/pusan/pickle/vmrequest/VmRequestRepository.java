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

    /** User list visibility: own requests + requests of workspaces I belong to. */
    @Query("select r from VmRequest r where r.requesterId = :userId or r.workspaceId in :workspaceIds")
    Page<VmRequest> findVisible(@Param("userId") Long userId,
            @Param("workspaceIds") Collection<Long> workspaceIds, Pageable pageable);

    @Query("""
            select r from VmRequest r
             where (r.requesterId = :userId or r.workspaceId in :workspaceIds) and r.status = :status
            """)
    Page<VmRequest> findVisibleByStatus(@Param("userId") Long userId,
            @Param("workspaceIds") Collection<Long> workspaceIds,
            @Param("status") VmRequestStatus status, Pageable pageable);

    Page<VmRequest> findByWorkspaceId(Long workspaceId, Pageable pageable);

    Page<VmRequest> findByWorkspaceIdAndStatus(Long workspaceId, VmRequestStatus status, Pageable pageable);

    /** All requests of a workspace in a given status (workspace-delete cancels its SUBMITTED ones). */
    List<VmRequest> findByWorkspaceIdAndStatus(Long workspaceId, VmRequestStatus status);

    Page<VmRequest> findByStatus(VmRequestStatus status, Pageable pageable);

    Page<VmRequest> findByOrgId(Long orgId, Pageable pageable);

    Page<VmRequest> findByOrgIdAndStatus(Long orgId, VmRequestStatus status, Pageable pageable);

    long countByRequesterIdAndStatus(Long requesterId, VmRequestStatus status);

    /**
     * Duplicate-slug guard at submission (v0.12.0): another SUBMITTED request
     * already asks for the same hostname/slug. APPROVED requests need no check
     * here — approval turned the slug into a vms.hostname row, which the
     * existsByHostname check covers.
     */
    boolean existsByDesiredSlugAndStatus(String desiredSlug, VmRequestStatus status);

    /** Approval-context history: prior requests by the same user or workspace. */
    @Query("""
            select r from VmRequest r
             where (r.requesterId = :userId or r.workspaceId = :workspaceId) and r.id <> :excludeId
            """)
    List<VmRequest> findHistory(@Param("userId") Long userId, @Param("workspaceId") Long workspaceId,
            @Param("excludeId") Long excludeId, Pageable pageable);
}
