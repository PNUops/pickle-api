package kr.ac.pusan.pickle.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserStatusChangeRepository extends JpaRepository<UserStatusChange, Long> {

    /** Admin user-detail history, newest first (id tiebreak for same-instant rows). */
    List<UserStatusChange> findByUserIdOrderByChangedAtDescIdDesc(Long userId);

    /**
     * The most recent transition into a status — {@code enable} reads the last
     * {@code DISABLED} row to restore its {@code fromStatus} (ACTIVE or
     * PENDING_VERIFICATION) instead of inventing ACTIVE.
     */
    Optional<UserStatusChange> findFirstByUserIdAndToStatusOrderByChangedAtDescIdDesc(Long userId,
            UserStatus toStatus);
}
