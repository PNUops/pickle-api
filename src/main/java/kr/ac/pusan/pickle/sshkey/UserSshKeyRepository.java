package kr.ac.pusan.pickle.sshkey;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface UserSshKeyRepository extends JpaRepository<UserSshKey, Long> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<UserSshKey> findByPublicId(UUID publicId);

    List<UserSshKey> findByUserIdOrderByCreatedAtAscIdAsc(Long userId);

    Optional<UserSshKey> findByIdAndUserId(Long id, Long userId);

    /** SSH gateway route resolution: the key an offered fingerprint maps to. */
    Optional<UserSshKey> findByFingerprintSha256(String fingerprintSha256);

    long countByUserId(Long userId);

    /** Withdrawal removes the user's registered keys so no fingerprint resolves to them. */
    void deleteByUserId(Long userId);

    /**
     * Best-effort {@code last_used_at} bump on a successful gateway auth. Runs in
     * its own transaction (REQUIRES_NEW) so it can write from the read-only route
     * resolution without making the whole lookup a write path.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true)
    @Query("update UserSshKey k set k.lastUsedAt = :now where k.id = :id")
    int touchLastUsedAt(@Param("id") Long id, @Param("now") Instant now);
}
