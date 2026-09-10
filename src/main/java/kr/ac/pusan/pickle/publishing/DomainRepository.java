package kr.ac.pusan.pickle.publishing;

import jakarta.persistence.LockModeType;
import java.time.Instant;
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
     *
     * <p>The kinds to count are passed as a list rather than derived by
     * excluding CUSTOM: the cap is a rule about the shared platform name space
     * under a root, and a kind that is not part of that name space must not
     * fall into the count by having been forgotten here.</p>
     */
    long countByVmIdAndKindInAndStatusNotAndReleasedAtIsNull(Long vmId,
            Collection<DomainKind> kinds, DomainStatus status);

    /** Released-but-kept rows — the reservation sweeper's scan set. */
    List<Domain> findByReleasedAtIsNotNullAndStatusNot(DomainStatus status);

    /**
     * Rows whose renewal deadline is at or before {@code bound} and that are
     * still held by their owner — the renewal sweep's candidates, both the
     * ones to warn and the ones already past. A released row is excluded
     * because its deadline stopped meaning anything the moment it was let go:
     * the reservation grace decides its name from then on.
     */
    List<Domain> findByKindAndStatusNotAndReleasedAtIsNullAndRenewDueAtLessThanEqual(
            DomainKind kind, DomainStatus status, Instant bound);

    /** Every domain row of a VM, any status — the deletion teardown sweep. */
    List<Domain> findByVmId(Long vmId);

    /**
     * Every live row under a platform root, serving or held in its grace —
     * the names the DNS orphan prune must leave alone.
     */
    List<Domain> findByRootDomainAndStatusNot(String rootDomain, DomainStatus status);

    /** Custom domains due for DNS re-check (recurring verification scan). */
    List<Domain> findByKindAndStatusIn(DomainKind kind, Collection<DomainStatus> statuses);

    // The listing narrowed to the VMs the requester may actually reach: a
    // domain that serves a VM is withheld unless the access list names them on
    // that VM.
    //
    // The null test is written out rather than left to `in`. A domain with no
    // VM is not reachable by this rule and must not be listed here, which is
    // also what `d.vmId in :vmIds` does — but for the wrong reason, because
    // `NULL IN (...)` is UNKNOWN rather than false. Spelled out, the exclusion
    // is a decision instead of an accident, and the branch that lists a domain
    // with no VM arrives with the access list that makes one reachable at all.
    @Query("""
            select d from Domain d
            where d.vmId is not null and d.vmId in :vmIds
              and (:vmId is null or d.vmId = :vmId)
              and ((:status is null and cast(d.status as string) <> 'REMOVED')
                   or cast(d.status as string) = :status)
            order by d.id desc
            """)
    Page<Domain> findForReachableVms(@Param("vmIds") Collection<Long> vmIds,
            @Param("vmId") Long vmId, @Param("status") String status, Pageable pageable);

    // Admin listing, scoped on the row's own organisation. It used to join Vm
    // for that, which is an inner join on a column that can now be null and
    // would drop those rows from the administrator's view entirely — a listing
    // that silently omits rows is worse than one that errors.
    // Enum filters are cast to string so a null bind has a determinable type.
    // REMOVED rows are hidden by default but visible via status=REMOVED.
    @Query("""
            select d from Domain d
            where (:orgIds is null or d.orgId in :orgIds)
              and (:kind is null or cast(d.kind as string) = :kind)
              and ((:status is null and cast(d.status as string) <> 'REMOVED')
                   or cast(d.status as string) = :status)
            order by d.id desc
            """)
    Page<Domain> findAdmin(@Param("orgIds") Collection<Long> orgIds, @Param("kind") String kind,
            @Param("status") String status, Pageable pageable);
}
