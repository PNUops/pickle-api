package kr.ac.pusan.pickle.request;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RequestRepository extends JpaRepository<Request, Long>,
        JpaSpecificationExecutor<Request> {

    /**
     * Locked lookup for decision/cancel mutations: concurrent decisions on the
     * same request serialize, so exactly one wins and the rest see 409.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Request r where r.id = :id")
    Optional<Request> findWithLockById(@Param("id") Long id);

    /** All requests of a workspace in a given status (workspace-delete cancels its SUBMITTED ones). */
    List<Request> findByWorkspaceIdAndStatus(Long workspaceId, RequestStatus status);

    long countByRequesterIdAndStatus(Long requesterId, RequestStatus status);

    /** Approval-context history: prior requests by the same user or workspace. */
    @Query("""
            select r from Request r
             where (r.requesterId = :userId or r.workspaceId = :workspaceId) and r.id <> :excludeId
            """)
    List<Request> findHistory(@Param("userId") Long userId, @Param("workspaceId") Long workspaceId,
            @Param("excludeId") Long excludeId, Pageable pageable);
}
