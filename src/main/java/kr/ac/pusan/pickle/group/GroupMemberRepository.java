package kr.ac.pusan.pickle.group;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {

    @Query("select gm from GroupMember gm join fetch gm.group where gm.userId = :userId order by gm.id")
    List<GroupMember> findWithGroupByUserId(@Param("userId") Long userId);

    boolean existsByUserIdAndGroupKind(Long userId, GroupKind kind);

    /** The single membership row used for service-layer authorization checks. */
    Optional<GroupMember> findByGroupIdAndUserId(Long groupId, Long userId);

    /**
     * Same lookup with a row lock: membership mutations lock the actor's row
     * first so concurrent ownership transfers / removals serialize and cannot
     * produce two OWNER rows.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select gm from GroupMember gm where gm.group.id = :groupId and gm.userId = :userId")
    Optional<GroupMember> findWithLockByGroupIdAndUserId(@Param("groupId") Long groupId,
            @Param("userId") Long userId);

    List<GroupMember> findByGroupIdOrderByIdAsc(Long groupId);

    long countByGroupIdAndRole(Long groupId, GroupMemberRole role);

    /** Member counts for the group-list view ({@code GroupSummary.memberCount}). */
    @Query("""
            select gm.group.id as groupId, count(gm.id) as memberCount
              from GroupMember gm
             where gm.group.id in :groupIds
             group by gm.group.id
            """)
    List<GroupMemberCount> countMembersByGroupIdIn(@Param("groupIds") Collection<Long> groupIds);

    interface GroupMemberCount {
        Long getGroupId();

        long getMemberCount();
    }
}
