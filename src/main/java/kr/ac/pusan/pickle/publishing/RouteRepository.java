package kr.ac.pusan.pickle.publishing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RouteRepository extends JpaRepository<Route, Long> {

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
