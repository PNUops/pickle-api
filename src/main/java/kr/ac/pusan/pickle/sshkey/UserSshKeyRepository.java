package kr.ac.pusan.pickle.sshkey;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserSshKeyRepository extends JpaRepository<UserSshKey, Long> {

    List<UserSshKey> findByUserIdOrderByCreatedAtAscIdAsc(Long userId);

    Optional<UserSshKey> findByIdAndUserId(Long id, Long userId);

    /** SSH gateway route resolution: the key an offered fingerprint maps to. */
    Optional<UserSshKey> findByFingerprintSha256(String fingerprintSha256);

    long countByUserId(Long userId);

    /** Best-effort {@code last_used_at} bump on a successful gateway auth. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update UserSshKey k set k.lastUsedAt = :now where k.id = :id")
    int touchLastUsedAt(@Param("id") Long id, @Param("now") Instant now);
}
