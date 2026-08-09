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

    /**
     * The VM row, locked as the serialization point for per-VM counted limits
     * (the platform-subdomain cap): a count-then-insert cap check is only
     * correct when concurrent inserts for the same VM cannot interleave
     * between the count and the commit. Taken by short, network-free
     * transactions only.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Vm v where v.id = :id")
    java.util.Optional<Vm> findByIdForUpdate(@Param("id") Long id);

    /** SSH gateway route resolution: the VM a client slug (hostname) maps to. */
    java.util.Optional<Vm> findByHostname(String hostname);

    /** VM ids of the given groups — for building an access-scoped id filter. */
    @Query("select v.id from Vm v where v.groupId in :groupIds")
    List<Long> findIdsByGroupIdIn(@Param("groupIds") Collection<Long> groupIds);

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

    /**
     * Currently allocated (= not deleted) VMs of an org, for headroom math —
     * a null {@code orgId} means platform-wide (SYS_ADMIN dashboard).
     */
    @Query("""
            select v from Vm v
             where (:orgId is null or v.orgId = :orgId)
               and v.deletedAt is null and v.status <> :deleted
             order by v.id
            """)
    List<Vm> findActiveByOrgId(@Param("orgId") Long orgId, @Param("deleted") VmStatus deleted);

    /**
     * Shared blocker query — group deletion and account withdrawal both refuse
     * while a group still has non-destroyed VMs (everything but DELETED,
     * DELETING included). Defined once so both callers bind to one predicate
     * instead of drifting apart.
     */
    @Query("""
            select count(v) from Vm v
             where v.groupId = :groupId and v.deletedAt is null and v.status <> :deleted
            """)
    long countActiveByGroupId(@Param("groupId") Long groupId, @Param("deleted") VmStatus deleted);

    /** Multi-group variant of {@link #countActiveByGroupId} (withdrawal scan). */
    @Query("""
            select count(v) from Vm v
             where v.groupId in :groupIds and v.deletedAt is null and v.status <> :deleted
            """)
    long countActiveByGroupIdIn(@Param("groupIds") Collection<Long> groupIds,
            @Param("deleted") VmStatus deleted);

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

    // --- power-action serialization -----------------------

    /**
     * Claims the single-writer power-action slot in one CAS: succeeds only
     * when no action is already in flight and the status is an allowed source.
     * Rapid duplicates therefore see exactly one success (1 row) and the rest
     * a 409 (0 rows). The claimed action name is informational; the worker
     * clears it on any exit path.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Vm v
               set v.pendingPowerAction = :action, v.pendingPowerActionAt = :now, v.updatedAt = :now
             where v.id = :id and v.pendingPowerAction is null and v.status in :statuses
            """)
    int claimPowerAction(@Param("id") Long id, @Param("action") String action,
            @Param("statuses") Collection<VmStatus> statuses, @Param("now") Instant now);

    /**
     * Reboot's intent: RUNNING → REBOOTING, but only when no other power action
     * is claimed. Unlike {@link #claimPowerAction} it does NOT set
     * pending_power_action — the visible REBOOTING status both serializes
     * duplicate reboots and lets a force-stop target a hung reboot, and it
     * keeps the status poller free to converge a crashed reboot job.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Vm v
               set v.status = :to, v.statusDetail = null, v.updatedAt = :now
             where v.id = :id and v.status = :from and v.pendingPowerAction is null
            """)
    int claimReboot(@Param("id") Long id, @Param("from") VmStatus from, @Param("to") VmStatus to,
            @Param("now") Instant now);

    /** Releases the power-action claim once the worker finishes (any exit path). */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Vm v
               set v.pendingPowerAction = null, v.pendingPowerActionAt = null, v.updatedAt = :now
             where v.id = :id and v.pendingPowerAction is not null
            """)
    int clearPowerActionClaim(@Param("id") Long id, @Param("now") Instant now);

    /** Crash recovery: frees power claims a dead worker never released. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Vm v
               set v.pendingPowerAction = null, v.pendingPowerActionAt = null, v.updatedAt = :now
             where v.pendingPowerAction is not null and v.pendingPowerActionAt < :cutoff
            """)
    int clearStalePowerActionClaims(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    // --- usage-period expiry (single-writer: VmExpiryJob / VmPeriodService) ---

    /** Expiry-notice working set: dated, live, no pending deletion, ending by the horizon. */
    @Query("""
            select v from Vm v
             where v.endDate is not null and v.endDate >= :today and v.endDate <= :horizon
               and v.status in :statuses
               and v.deleteScheduledFor is null and v.deleteRequestedAt is null
             order by v.id
            """)
    List<Vm> findExpiryNoticeCandidates(@Param("statuses") Collection<VmStatus> statuses,
            @Param("today") java.time.LocalDate today,
            @Param("horizon") java.time.LocalDate horizon);

    /** Auto-stop working set: past end date, still powered, not yet expiry-stopped. */
    @Query("""
            select v from Vm v
             where v.endDate is not null and v.endDate < :today
               and v.status in :statuses and v.expiryStoppedAt is null
               and v.deleteScheduledFor is null and v.deleteRequestedAt is null
             order by v.id
            """)
    List<Vm> findExpiryStopCandidates(@Param("statuses") Collection<VmStatus> statuses,
            @Param("today") java.time.LocalDate today);

    /**
     * Notice-stage CAS: descends only (14 → 7 → 1). An hourly re-run or a
     * duplicate worker loses the guard and sends nothing; extending the period
     * clears the stage (see {@link #updatePeriod}) so notices re-arm.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Vm v
               set v.lastExpiryNoticeStage = :stage, v.updatedAt = :now
             where v.id = :id
               and (v.lastExpiryNoticeStage is null or v.lastExpiryNoticeStage > :stage)
            """)
    int markExpiryNoticeStage(@Param("id") Long id, @Param("stage") int stage,
            @Param("now") Instant now);

    /** Confirmed expiry stop: status → STOPPED with the expiry marker, in one CAS. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Vm v
               set v.status = :to, v.statusDetail = :statusDetail, v.expiryStoppedAt = :now,
                   v.updatedAt = :now
             where v.id = :id and v.status in :from and v.expiryStoppedAt is null
            """)
    int finishExpiryStop(@Param("id") Long id, @Param("from") Collection<VmStatus> from,
            @Param("to") VmStatus to, @Param("statusDetail") String statusDetail,
            @Param("now") Instant now);

    /**
     * Admin period change: updates the dates and clears both expiry markers in
     * the same CAS, guarded against deletion states so a raced schedule-delete
     * cannot be overridden (0 rows → 409 {@code VM_INVALID_STATE}).
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Vm v
               set v.startDate = :startDate, v.endDate = :endDate, v.expiryStoppedAt = null,
                   v.lastExpiryNoticeStage = null, v.updatedAt = :now
             where v.id = :id and v.status not in :excluded
               and v.deleteScheduledFor is null and v.deleteRequestedAt is null
            """)
    int updatePeriod(@Param("id") Long id, @Param("startDate") java.time.LocalDate startDate,
            @Param("endDate") java.time.LocalDate endDate,
            @Param("excluded") Collection<VmStatus> excluded, @Param("now") Instant now);

    /**
     * Per-VM SSH-gateway/web-terminal block toggle. Declarative flag with no
     * state guard — blocking a DELETED VM is harmless and unblocking must
     * always be possible. The entity stays setter-free. The transition is
     * CAS-style ({@code <> :blocked}) so concurrent opposite toggles each
     * either win the flip (and record it) or observe 0 rows — an admin's
     * intent is never silently dropped by a stale pre-read.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Vm v set v.sshGatewayBlocked = :blocked, v.updatedAt = :now
             where v.id = :id and v.sshGatewayBlocked <> :blocked
            """)
    int updateSshGatewayBlocked(@Param("id") Long id, @Param("blocked") boolean blocked,
            @Param("now") Instant now);

    // --- provision pipeline column updates (single-writer: the provisioning job) ----

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
     * Step 5 (config) and password reset store the generated credentials:
     * AES-GCM ciphertext for the (re-)viewable endpoint, BCrypt hash for
     * support verification (initial credentials). The plaintext
     * is never persisted or logged anywhere.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Vm v
               set v.passwordEnc = :passwordEnc, v.passwordHash = :passwordHash,
                   v.passwordViewedAt = null, v.updatedAt = :now
             where v.id = :id
            """)
    int storeCredentials(@Param("id") Long id, @Param("passwordEnc") String passwordEnc,
            @Param("passwordHash") String passwordHash, @Param("now") Instant now);

    /** Records the (latest) reveal time — informational only since v0.7.0. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Vm v set v.passwordViewedAt = :now where v.id = :id")
    int recordPasswordViewed(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Provisioning HOSTKEY step: pins the collected SSH host key. Idempotent — a
     * re-run overwrites with the same collected value (the internal SSH gateway route contract).
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Vm v set v.sshHostKey = :hostKey, v.updatedAt = :now where v.id = :id")
    int storeSshHostKey(@Param("id") Long id, @Param("hostKey") String hostKey,
            @Param("now") Instant now);

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

    // ── deletion lifecycle (contract v0.3.1) ─────────────────

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
     * the self-delete collapses to an immediate DELETED, no pipeline. The
     * stored initial-password ciphertext is wiped like in {@link #markDeleted}.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            update vms
               set status = 'DELETED', status_detail = null, delete_kind = 'SELF',
                   delete_scheduled_for = :now, delete_requested_at = :now,
                   delete_requested_by = :requestedBy, delete_reason = null,
                   password_enc = null,
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
     * SYS_ADMIN force delete: immediate DELETING + FORCE intent,
     * overriding a pending SELF/ADMIN deletion (schedule-delete's escape
     * hatch for "destroy now").
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            update vms
               set status = 'DELETING', status_detail = null, delete_kind = 'FORCE',
                   delete_scheduled_for = :now, delete_requested_at = :now,
                   delete_requested_by = :requestedBy, delete_reason = null, updated_at = :now
             where id = :id and status <> 'DELETED'
            """)
    int beginForceDeletion(@Param("id") Long id, @Param("requestedBy") Long requestedBy,
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

    /**
     * Cancels an ADMIN-scheduled deletion in one CAS. Valid until destruction
     * actually claims the VM (status flips to DELETING) — not until the wall
     * clock passes the schedule: the schedule may sit minutes past due before
     * the sweeper fires, and an intact VM must stay cancelable in that window.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            update vms
               set delete_kind = null, delete_scheduled_for = null, delete_requested_at = null,
                   delete_requested_by = null, delete_reason = null, updated_at = :now
             where id = :id and delete_kind = 'ADMIN' and status not in ('DELETING', 'DELETED')
            """)
    int cancelAdminDeletion(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Destroy-time claim: flips a power state to DELETING only while the
     * delete intent still stands AND is actually due. The intent check keeps a
     * cancel racing the delete job from stranding an intent-less VM in
     * DELETING; the due-time check keeps a stale enqueued job (whose original
     * intent was canceled) from executing a NEWER, future-scheduled intent
     * years early.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            update vms
               set status = 'DELETING', status_detail = null, updated_at = :now
             where id = :id and status = cast(:#{#from.name()} as vm_status)
               and delete_kind is not null and delete_scheduled_for <= :now
            """)
    int claimForDestruction(@Param("id") Long id, @Param("from") VmStatus from,
            @Param("now") Instant now);

    /**
     * Final destruction: DELETING → DELETED, keeping the row forever — except
     * the stored initial-password ciphertext: credentials must not outlive the
     * VM (the BCrypt hash stays for support verification).
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            update vms
               set status = 'DELETED', status_detail = null, password_enc = null,
                   deleted_at = :now, deleted_by = :deletedBy, updated_at = :now
             where id = :id and status = 'DELETING'
            """)
    int markDeleted(@Param("id") Long id, @Param("deletedBy") Long deletedBy,
            @Param("now") Instant now);

    /**
     * Deletion-sweeper scan: pending deletions whose destroy time arrived.
     * SELF/FORCE VMs are already DELETING; ADMIN-scheduled ones sit in a
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
