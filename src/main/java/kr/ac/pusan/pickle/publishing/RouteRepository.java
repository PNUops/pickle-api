package kr.ac.pusan.pickle.publishing;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RouteRepository extends JpaRepository<Route, Long> {

    /**
     * The route, locked for one SHORT apply phase — the prepare that reads the
     * desired state and the record that writes the outcome each take this lock
     * for their own transaction; it is never held across the agent call.
     * Cross-call safety comes from the generation instead: the record phase
     * writes only if the generation is still the one the prepare read, so a
     * slower apply can never commit a state a faster writer already superseded.
     * That is not theoretical — an apply enqueued when a domain was created,
     * still in flight when the VM was deleted, once wrote its route back to
     * APPLIED after the deletion had marked it REMOVED, and the vhost went on
     * serving a VM that no longer existed. Routes of different domains never
     * contend.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Route r where r.id = :id")
    Optional<Route> findByIdForApply(@Param("id") Long id);

    /** The live route for a domain (v1: one route per domain). */
    Optional<Route> findFirstByDomainIdAndStatusNot(Long domainId, RouteStatus status);

    /**
     * Routes whose desired state the agent has not confirmed ({@code
     * appliedGeneration < generation}) and that are worth re-pushing: a REMOVED
     * route always (the vhost may still be serving), a PENDING one only once its
     * domain is ACTIVE (an unverified custom domain must never go live). FAILED
     * is excluded — a 422 is a definitive agent verdict, re-pushing the same
     * config would thrash. {@code cutoff} skips routes still settling through
     * their own enqueued apply ({@link RouteReconcileJob}).
     */
    @Query("""
            select r.id from Route r
              join Domain d on d.id = r.domainId
            where (r.appliedGeneration is null or r.appliedGeneration < r.generation)
              and r.updatedAt < :cutoff
              and (cast(r.status as string) = 'REMOVED'
                   or (cast(r.status as string) = 'PENDING'
                       and cast(d.status as string) = 'ACTIVE'))
            order by r.id
            """)
    List<Long> findUnconfirmedRouteIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    /** Every live route — the sync-all manifest source. */
    List<Route> findByStatusNot(RouteStatus status);

    /**
     * The live route holding {@code fqdn} under a DIFFERENT live domain row —
     * the name's new owner after an immediate release (custom domains free
     * their FQDN the moment they are deleted). At most one exists: the partial
     * unique index allows a single live domain per FQDN. Consulted before a
     * dead route's removal is pushed, so the removal of a name that changed
     * hands cannot take the new owner's vhost down.
     */
    @Query("""
            select r from Route r
              join Domain d on d.id = r.domainId
            where d.fqdn = :fqdn and d.id <> :domainId
              and cast(d.status as string) <> 'REMOVED'
              and cast(r.status as string) <> 'REMOVED'
            """)
    Optional<Route> findLiveClaimant(@Param("fqdn") String fqdn, @Param("domainId") Long domainId);

    /**
     * Revives a route for a verify-triggered re-apply in one CAS — an
     * unpublish/teardown that flipped the route REMOVED meanwhile must never
     * be overwritten back to PENDING (that would re-push a vhost the user
     * just took down). Native SQL for the same reason as the VM CAS updates:
     * HQL enum literals cast to the Java type name, not the pg enum.
     * flushAutomatically so the verifier's pending domain/cert changes are
     * written before the context is cleared.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            update routes
               set generation = :generation, status = 'PENDING', last_error = null,
                   updated_at = :now
             where id = :id and status <> 'REMOVED'
            """)
    int reviveForReapply(@Param("id") Long id, @Param("generation") long generation,
            @Param("now") Instant now);

    /**
     * Records a sync-all confirmation for one route in one CAS: the write
     * lands only if the route still carries the generation the manifest was
     * rendered from AND is still live. A teardown that flipped the route
     * REMOVED (bumping its generation) while the sync-all call was on the wire
     * must never be overwritten back to APPLIED — that resurrects a vhost the
     * user just took down and, for a platform name, parks its reservation
     * as "still serving" forever. Native SQL for the same enum-literal reason
     * as {@link #reviveForReapply}; {@code updated_at} is set by hand because
     * native SQL bypasses the entity's update timestamp.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            update routes
               set status = 'APPLIED', applied_generation = generation,
                   applied_at = :now, last_error = null, updated_at = :now
             where id = :id and generation = :generation and status <> 'REMOVED'
            """)
    int confirmSyncedRoute(@Param("id") Long id, @Param("generation") long generation,
            @Param("now") Instant now);

    Optional<Route> findFirstByDomainId(Long domainId);

    @Query("""
            select r from Route r
              join Domain d on d.id = r.domainId
              join kr.ac.pusan.pickle.vm.Vm v on v.id = d.vmId
            where (:orgId is null or v.orgId = :orgId)
              and (:status is null or cast(r.status as string) = :status)
            order by r.id desc
            """)
    Page<Route> findAdmin(@Param("orgId") Long orgId, @Param("status") String status,
            Pageable pageable);
}
