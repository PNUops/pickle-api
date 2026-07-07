package kr.ac.pusan.pickle.vm;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VmRepository extends JpaRepository<Vm, Long> {

    boolean existsByHostname(String hostname);

    Page<Vm> findByGroupIdIn(Collection<Long> groupIds, Pageable pageable);

    Page<Vm> findByGroupId(Long groupId, Pageable pageable);

    /** Currently allocated (= not deleted) VMs of the given groups. */
    @Query("""
            select v from Vm v
             where v.groupId in :groupIds and v.deletedAt is null and v.status <> :deleted
             order by v.id
            """)
    List<Vm> findActiveByGroupIdIn(@Param("groupIds") Collection<Long> groupIds,
            @Param("deleted") VmStatus deleted);

    /** Currently allocated (= not deleted) VMs of an org, for headroom math. */
    @Query("""
            select v from Vm v
             where v.orgId = :orgId and v.deletedAt is null and v.status <> :deleted
             order by v.id
            """)
    List<Vm> findActiveByOrgId(@Param("orgId") Long orgId, @Param("deleted") VmStatus deleted);

    /**
     * Compare-and-set status transition; the {@code from} guard makes job
     * re-runs idempotent (0 rows updated when the VM already moved on).
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update Vm v
               set v.status = :to, v.statusDetail = :statusDetail, v.updatedAt = :now
             where v.id = :id and v.status = :from
            """)
    int transitionStatus(@Param("id") Long id, @Param("from") VmStatus from, @Param("to") VmStatus to,
            @Param("statusDetail") String statusDetail, @Param("now") Instant now);
}
