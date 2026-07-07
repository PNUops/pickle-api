package kr.ac.pusan.pickle.group;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {

    @Query("select gm from GroupMember gm join fetch gm.group where gm.userId = :userId order by gm.id")
    List<GroupMember> findWithGroupByUserId(@Param("userId") Long userId);

    boolean existsByUserIdAndGroupKind(Long userId, GroupKind kind);
}
