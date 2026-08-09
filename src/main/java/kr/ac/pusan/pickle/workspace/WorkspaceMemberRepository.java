package kr.ac.pusan.pickle.workspace;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Long> {

    /**
     * A user's memberships with their workspaces fetched, excluding soft-deleted
     * workspaces — this is the single derived-workspaces query behind the workspace
     * list, profile, VM-request scope, publishing scope and org derivation, so
     * a deleted workspace disappears from all of them at once.
     */
    @Query("""
            select gm from WorkspaceMember gm join fetch gm.workspace g
             where gm.userId = :userId and g.deletedAt is null
             order by gm.id
            """)
    List<WorkspaceMember> findWithWorkspaceByUserId(@Param("userId") Long userId);

    boolean existsByUserIdAndWorkspaceKind(Long userId, WorkspaceKind kind);

    /** The single membership row used for service-layer authorization checks. */
    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    /**
     * Same lookup with a row lock: membership mutations lock the actor's row
     * first so concurrent ownership transfers / removals serialize and cannot
     * produce two OWNER rows.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select gm from WorkspaceMember gm where gm.workspace.id = :workspaceId and gm.userId = :userId")
    Optional<WorkspaceMember> findWithLockByWorkspaceIdAndUserId(@Param("workspaceId") Long workspaceId,
            @Param("userId") Long userId);

    List<WorkspaceMember> findByWorkspaceIdOrderByIdAsc(Long workspaceId);

    long countByWorkspaceIdAndRole(Long workspaceId, WorkspaceMemberRole role);

    /** Withdrawal drops all of the departing user's memberships in one statement. */
    void deleteByUserId(Long userId);

    /** Member counts for the workspace-list view ({@code WorkspaceSummary.memberCount}). */
    @Query("""
            select gm.workspace.id as workspaceId, count(gm.id) as memberCount
              from WorkspaceMember gm
             where gm.workspace.id in :workspaceIds
             group by gm.workspace.id
            """)
    List<WorkspaceMemberCount> countMembersByWorkspaceIdIn(@Param("workspaceIds") Collection<Long> workspaceIds);

    interface WorkspaceMemberCount {
        Long getWorkspaceId();

        long getMemberCount();
    }
}
