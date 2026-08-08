package kr.ac.pusan.pickle.publishing;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DomainRepository extends JpaRepository<Domain, Long> {

    /** The live row holding an FQDN — the revive-or-409 pre-check. */
    Optional<Domain> findFirstByFqdnAndStatusNot(String fqdn, DomainStatus status);

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
     * User listing — scoped to the caller's own groups' VMs. {@code vmId} and
     * {@code status} are optional filters; newest first. REMOVED rows are
     * hidden by default but visible via status=REMOVED — same convention as
     * the admin listing: a removed domain is gone from its owner's view, not
     * a row that lingers as if it still held its name.
     */
    @Query("""
            select d from Domain d join kr.ac.pusan.pickle.vm.Vm v on v.id = d.vmId
            where v.groupId in :groupIds
              and (:vmId is null or d.vmId = :vmId)
              and ((:status is null and cast(d.status as string) <> 'REMOVED')
                   or cast(d.status as string) = :status)
            order by d.id desc
            """)
    Page<Domain> findForMember(@Param("groupIds") Collection<Long> groupIds, @Param("vmId") Long vmId,
            @Param("status") String status, Pageable pageable);

    // Admin listing — joined to Vm for org scoping (ad-hoc join on the FK column).
    // Enum filters are cast to string so a null bind has a determinable type.
    // REMOVED rows are hidden by default but visible via status=REMOVED.
    @Query("""
            select d from Domain d join kr.ac.pusan.pickle.vm.Vm v on v.id = d.vmId
            where (:orgId is null or v.orgId = :orgId)
              and (:kind is null or cast(d.kind as string) = :kind)
              and ((:status is null and cast(d.status as string) <> 'REMOVED')
                   or cast(d.status as string) = :status)
            order by d.id desc
            """)
    Page<Domain> findAdmin(@Param("orgId") Long orgId, @Param("kind") String kind,
            @Param("status") String status, Pageable pageable);
}
