package kr.ac.pusan.pickle.vm;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface VmRepository extends JpaRepository<Vm, Long>, JpaSpecificationExecutor<Vm> {

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

    /** VMs the status poller may look at: a Proxmox identity and a pollable status. */
    List<Vm> findByProxmoxVmidIsNotNullAndStatusIn(Collection<VmStatus> statuses);

    /** All VMs with a Proxmox identity (drift-reconciler working set). */
    List<Vm> findByProxmoxVmidIsNotNull();

    /** Stale-recovery scan, e.g. stuck-CREATING VMs whose enqueue was lost. */
    List<Vm> findByStatusAndUpdatedAtBefore(VmStatus status, Instant cutoff);

    /**
     * Compare-and-set status transition; the {@code from} guard makes job
     * re-runs idempotent (0 rows updated when the VM already moved on).
     * {@code @Transactional} lets transactionless callers (recurring jobs)
     * use it directly; transactional callers simply join.
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

    /**
     * Clears the IP pointer after a successful release, guarded by the
     * allocation id so a re-allocated pointer is never wiped by a stale run.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Vm v
               set v.ipAllocationId = null, v.updatedAt = :now
             where v.id = :id and v.ipAllocationId = :allocationId
            """)
    int clearIpAllocation(@Param("id") Long id, @Param("allocationId") Long allocationId,
            @Param("now") Instant now);

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

    /**
     * Sets the informational {@code status_detail} without a state transition
     * (drift class ③). Guarded by the current status so a concurrent pipeline
     * transition wins and the note is dropped instead of clobbering.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Vm v
               set v.statusDetail = :statusDetail, v.updatedAt = :now
             where v.id = :id and v.status = :status
            """)
    int updateStatusDetail(@Param("id") Long id, @Param("status") VmStatus status,
            @Param("statusDetail") String statusDetail, @Param("now") Instant now);

    /** Records a failure/drift note without touching the status (power jobs). */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Vm v
               set v.statusDetail = :statusDetail, v.updatedAt = :now
             where v.id = :id
            """)
    int updateStatusDetail(@Param("id") Long id, @Param("statusDetail") String statusDetail,
            @Param("now") Instant now);

    // ── deletion lifecycle (docs/plan/03, contract v0.3.1) ─────────────────

    // Deletion CAS updates are native SQL: HQL renders enum *literals* with a
    // cast to the Java type name ('DELETING'::VmStatus), which does not match
    // the postgres enum type (vm_status). Bound enum *parameters* are fine.

    /**
     * Self-delete acceptance in one CAS: DELETING transition + SELF deletion
     * intent. Guarded by the caller-observed status and no pending deletion.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            update vms
               set status = 'DELETING', status_detail = null, delete_kind = 'SELF',
                   delete_scheduled_for = :scheduledFor, delete_requested_at = :now,
                   delete_requested_by = :requestedBy, delete_reason = null, updated_at = :now
             where id = :id and status = cast(:#{#from.name()} as vm_status) and delete_kind is null
            """)
    int beginSelfDeletion(@Param("id") Long id, @Param("from") VmStatus from,
            @Param("scheduledFor") Instant scheduledFor, @Param("requestedBy") Long requestedBy,
            @Param("now") Instant now);

    /**
     * ERROR VMs have no substrate to destroy (compensation already ran):
     * the self-delete collapses to an immediate DELETED, no pipeline.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            update vms
               set status = 'DELETED', status_detail = null, delete_kind = 'SELF',
                   delete_scheduled_for = :now, delete_requested_at = :now,
                   delete_requested_by = :requestedBy, delete_reason = null,
                   deleted_at = :now, deleted_by = :requestedBy, updated_at = :now
             where id = :id and status = 'ERROR' and delete_kind is null
            """)
    int completeErrorDeletion(@Param("id") Long id, @Param("requestedBy") Long requestedBy,
            @Param("now") Instant now);

    /** Admin-scheduled routine delete: intent only, the power state is untouched. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            update vms
               set delete_kind = 'ADMIN', delete_scheduled_for = :scheduledFor,
                   delete_requested_at = :now, delete_requested_by = :requestedBy,
                   delete_reason = :reason, updated_at = :now
             where id = :id and delete_kind is null
            """)
    int scheduleAdminDeletion(@Param("id") Long id, @Param("scheduledFor") Instant scheduledFor,
            @Param("requestedBy") Long requestedBy, @Param("reason") String reason,
            @Param("now") Instant now);

    /**
     * SYS_ADMIN emergency delete: immediate DELETING + EMERGENCY intent,
     * overriding a pending SELF/ADMIN deletion (schedule-delete's escape
     * hatch for "destroy now").
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            update vms
               set status = 'DELETING', status_detail = null, delete_kind = 'EMERGENCY',
                   delete_scheduled_for = :now, delete_requested_at = :now,
                   delete_requested_by = :requestedBy, delete_reason = null, updated_at = :now
             where id = :id and status <> 'DELETED'
            """)
    int beginEmergencyDeletion(@Param("id") Long id, @Param("requestedBy") Long requestedBy,
            @Param("now") Instant now);

    /** Cancels the pending deletion by clearing every delete_* column. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            update vms
               set delete_kind = null, delete_scheduled_for = null, delete_requested_at = null,
                   delete_requested_by = null, delete_reason = null, updated_at = :now
             where id = :id and delete_kind is not null
            """)
    int clearDeletion(@Param("id") Long id, @Param("now") Instant now);

    /** Final destruction: DELETING → DELETED, keeping the row forever. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            update vms
               set status = 'DELETED', status_detail = null,
                   deleted_at = :now, deleted_by = :deletedBy, updated_at = :now
             where id = :id and status = 'DELETING'
            """)
    int markDeleted(@Param("id") Long id, @Param("deletedBy") Long deletedBy,
            @Param("now") Instant now);

    /**
     * Deletion-sweeper scan: pending deletions whose destroy time arrived.
     * SELF/EMERGENCY VMs are already DELETING; ADMIN-scheduled ones sit in a
     * normal power state until due. NEEDS_ADMIN and DELETED never match, so a
     * parked delete pipeline is not re-enqueued behind the operator's back.
     */
    @Query("""
            select v from Vm v
             where v.deleteKind is not null and v.deleteScheduledFor <= :now
               and v.status in :statuses
             order by v.id
            """)
    List<Vm> findDueForDeletion(@Param("now") Instant now,
            @Param("statuses") Collection<VmStatus> statuses);
}
