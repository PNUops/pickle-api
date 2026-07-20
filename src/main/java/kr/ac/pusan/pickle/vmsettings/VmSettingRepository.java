package kr.ac.pusan.pickle.vmsettings;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VmSettingRepository extends JpaRepository<VmSetting, Long> {

    List<VmSetting> findByVmId(Long vmId);

    Optional<VmSetting> findByVmIdAndKey(Long vmId, String key);

    /** Batch load one key across many VMs (list views — avoids N+1 on display_name). */
    List<VmSetting> findByKeyAndVmIdIn(String key, Collection<Long> vmIds);

    /**
     * Direct DB write of a BOOLEAN key to {@code false} (force-delete override).
     * Deliberately not an entity mutation: the same row is typically already
     * loaded in the surrounding transaction by the enforcement getter — via a
     * read-only path whose instance skips dirty checking, so an entity-level
     * update would silently not flush. The direct UPDATE also makes the write
     * last-write-wins against a racing owner PATCH. Returns 0 when no override
     * row exists yet (caller inserts instead).
     */
    @Modifying
    @Query(value = "update vm_settings set value = to_jsonb(false), updated_by = :actorId, "
            + "updated_at = :now where vm_id = :vmId and key = :key", nativeQuery = true)
    int forceValueFalse(@Param("vmId") long vmId, @Param("key") String key,
            @Param("actorId") Long actorId, @Param("now") Instant now);
}
