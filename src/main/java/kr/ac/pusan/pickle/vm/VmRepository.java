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
