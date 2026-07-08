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
import org.springframework.transaction.annotation.Transactional;

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

    /** DB-intent capacity already granted on a node, for placement scoring. */
    @Query("""
            select coalesce(sum(v.vcpu), 0) as vcpu, coalesce(sum(v.memoryMb), 0) as memoryMb
              from Vm v
             where v.nodeId = :nodeId and v.deletedAt is null and v.status <> :deleted
            """)
    AllocatedCapacity sumActiveByNodeId(@Param("nodeId") Long nodeId, @Param("deleted") VmStatus deleted);

    interface AllocatedCapacity {
        long getVcpu();

        long getMemoryMb();
    }

    /**
     * Compare-and-set status transition; the {@code from} guard makes job
     * re-runs idempotent (0 rows updated when the VM already moved on).
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Vm v
               set v.status = :to, v.statusDetail = :statusDetail, v.updatedAt = :now
             where v.id = :id and v.status = :from
            """)
    int transitionStatus(@Param("id") Long id, @Param("from") VmStatus from, @Param("to") VmStatus to,
            @Param("statusDetail") String statusDetail, @Param("now") Instant now);

    // --- M3 pipeline column updates (single-writer: the provisioning job) ----

    /** Step 1 (place) confirms/overrides the node chosen at approval time. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Vm v set v.nodeId = :nodeId, v.updatedAt = :now where v.id = :id")
    int assignNode(@Param("id") Long id, @Param("nodeId") Long nodeId, @Param("now") Instant now);

    /** Step 2 (alloc IP); the null guard keeps crashed re-runs idempotent. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Vm v
               set v.ipAllocationId = :allocationId, v.updatedAt = :now
             where v.id = :id and v.ipAllocationId is null
            """)
    int assignIpAllocation(@Param("id") Long id, @Param("allocationId") Long allocationId,
            @Param("now") Instant now);

    /** Step 3 (vmid); the null guard makes a crashed re-run reuse the stored id. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Vm v
               set v.proxmoxVmid = :vmid, v.updatedAt = :now
             where v.id = :id and v.proxmoxVmid is null
            """)
    int assignProxmoxVmid(@Param("id") Long id, @Param("vmid") Integer vmid, @Param("now") Instant now);

    /** Compensation after steps 3–5 failed: the half-created VM is gone. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Vm v set v.proxmoxVmid = null, v.updatedAt = :now where v.id = :id")
    int clearProxmoxVmid(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Step 5 (config) stores the generated initial credentials: plaintext for
     * the one-shot view endpoint, BCrypt hash for support verification
     * (docs/plan/03 initial credentials). Never logged anywhere.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Vm v
               set v.initialPassword = :password, v.initialPasswordHash = :passwordHash,
                   v.initialPasswordViewedAt = null, v.updatedAt = :now
             where v.id = :id
            """)
    int storeInitialCredentials(@Param("id") Long id, @Param("password") String password,
            @Param("passwordHash") String passwordHash, @Param("now") Instant now);
}
