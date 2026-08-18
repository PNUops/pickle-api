package kr.ac.pusan.pickle.sshkey;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface VmSshKeyRepository extends JpaRepository<VmSshKey, Long> {

    /** The one key this person holds for this VM, if any. */
    Optional<VmSshKey> findByVmIdAndUserId(Long vmId, Long userId);

    /** SSH gateway route resolution: the key an offered fingerprint maps to. */
    Optional<VmSshKey> findByFingerprintSha256(String fingerprintSha256);

    /** Withdrawal removes the person's keys so no fingerprint resolves to them. */
    void deleteByUserId(Long userId);

    /**
     * Destroying a VM takes its keys with it. The gateway would refuse them
     * anyway (a destroyed VM is not RUNNING), so this is not the access control
     * — it is not keeping private-key ciphertext for a machine that is gone.
     */
    void deleteByVmId(Long vmId);

    /**
     * Best-effort {@code last_used_at} bump on a successful gateway auth. Runs in
     * its own transaction (REQUIRES_NEW) so it can write from the read-only route
     * resolution without making the whole lookup a write path.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true)
    @Query("update VmSshKey k set k.lastUsedAt = :now where k.id = :id")
    int touchLastUsedAt(@Param("id") Long id, @Param("now") Instant now);
}
