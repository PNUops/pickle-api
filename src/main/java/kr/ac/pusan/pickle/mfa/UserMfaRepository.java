package kr.ac.pusan.pickle.mfa;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserMfaRepository extends JpaRepository<UserMfa, Long> {

    /** True once the account has a confirmed, active TOTP secret. */
    @Query("select case when count(m) > 0 then true else false end "
            + "from UserMfa m where m.userId = :userId and m.enabledAt is not null")
    boolean isEnrolled(long userId);

    /** Enrolled user ids among the given set — one query for an admin-list page. */
    @Query("select m.userId from UserMfa m where m.userId in :userIds and m.enabledAt is not null")
    List<Long> findEnrolledUserIds(Collection<Long> userIds);

    @Modifying
    @Query("delete from UserMfa m where m.userId = :userId")
    void deleteByUserId(long userId);
}
