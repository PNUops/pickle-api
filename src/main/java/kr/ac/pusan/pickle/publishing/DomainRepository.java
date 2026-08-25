package kr.ac.pusan.pickle.publishing;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DomainRepository extends JpaRepository<Domain, Long> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<Domain> findByPublicId(UUID publicId);

    /** The live row holding an FQDN — the revive-or-409 pre-check. */
    Optional<Domain> findFirstByFqdnAndStatusNot(String fqdn, DomainStatus status);

    /**
     * The live row holding an FQDN, taken under its row lock — what the revive
     * path reads instead of the unlocked pre-check. The reservation sweeper
     * reclaims under the domain row lock and "first commit wins": a revive
     * working from an unlocked snapshot could re-attach a route to a row the
     * sweep flipped REMOVED in between, returning success for a dead domain.
     * Locked, the predicate is re-evaluated against the row's current committed
     * state, so a row reclaimed since the snapshot simply drops out (empty →
     * the name is free; the partial unique index arbitrates the fresh insert).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Domain d where d.fqdn = :fqdn and d.status <> :status")
    Optional<Domain> findFirstByFqdnAndStatusNotForUpdate(@Param("fqdn") String fqdn,
            @Param("status") DomainStatus status);

    /**
     * The domain row, locked for one short decide-and-write transaction. The
     * reservation sweeper takes this before reclaiming: its scan list is a
     * snapshot, and a revive committing between the scan and the reclaim
     * (clearing {@code releasedAt}, re-attaching a route) must not be
     * overwritten by that snapshot — the user got a success response. Under the
     * lock the row is re-read as committed, so the recheck sees the revive.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Domain d where d.id = :id")
    Optional<Domain> findByIdForUpdate(@Param("id") Long id);

    /**
     * SERVING platform subdomains of a VM (released ones excluded) — the
     * per-VM cap counts these only, so a name in its reservation grace never
     * blocks its owner from attaching a replacement.
     */
    long countByVmIdAndKindNotAndStatusNotAndReleasedAtIsNull(Long vmId, DomainKind kind,
            DomainStatus status);

    /** Released-but-kept rows — the reservation sweeper's scan set. */
    List<Domain> findByReleasedAtIsNotNullAndStatusNot(DomainStatus status);

    /** Every domain row of a VM, any status — the deletion teardown sweep. */
    List<Domain> findByVmId(Long vmId);

    /** Custom domains due for DNS re-check (recurring verification scan). */
    List<Domain> findByKindAndStatusIn(DomainKind kind, Collection<DomainStatus> statuses);

    /**
     * User listing — scoped to the caller's own workspaces' VMs. {@code vmId} and
     * {@code status} are optional filters; newest first. REMOVED rows are
     * hidden by default but visible via status=REMOVED — same convention as
     * the admin listing: a removed domain is gone from its owner's view, not
     * a row that lingers as if it still held its name.
     */
    @Query("""
            select d from Domain d join kr.ac.pusan.pickle.vm.Vm v on v.id = d.vmId
            where v.workspaceId in :workspaceIds
              and (:vmId is null or d.vmId = :vmId)
              and ((:status is null and cast(d.status as string) <> 'REMOVED')
                   or cast(d.status as string) = :status)
            order by d.id desc
            """)
    Page<Domain> findForMember(@Param("workspaceIds") Collection<Long> workspaceIds, @Param("vmId") Long vmId,
            @Param("status") String status, Pageable pageable);

    // Same listing narrowed to the VMs the requester may actually reach: a
    // domain names its VM, so listing one they hold no grant on would hand back
    // exactly what the access list is there to withhold.
    @Query("""
            select d from Domain d
            where d.vmId in :vmIds
              and (:vmId is null or d.vmId = :vmId)
              and ((:status is null and cast(d.status as string) <> 'REMOVED')
                   or cast(d.status as string) = :status)
            order by d.id desc
            """)
    Page<Domain> findForReachableVms(@Param("vmIds") Collection<Long> vmIds,
            @Param("vmId") Long vmId, @Param("status") String status, Pageable pageable);

    // Admin listing — joined to Vm for org scoping (ad-hoc join on the FK column).
    // Enum filters are cast to string so a null bind has a determinable type.
    // REMOVED rows are hidden by default but visible via status=REMOVED.
    @Query("""
            select d from Domain d join kr.ac.pusan.pickle.vm.Vm v on v.id = d.vmId
            where (:orgIds is null or v.orgId in :orgIds)
              and (:kind is null or cast(d.kind as string) = :kind)
              and ((:status is null and cast(d.status as string) <> 'REMOVED')
                   or cast(d.status as string) = :status)
            order by d.id desc
            """)
    Page<Domain> findAdmin(@Param("orgIds") Collection<Long> orgIds, @Param("kind") String kind,
            @Param("status") String status, Pageable pageable);
}
