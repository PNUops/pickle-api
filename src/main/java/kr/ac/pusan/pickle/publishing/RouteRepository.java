package kr.ac.pusan.pickle.publishing;

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

    /** Every live route — the sync-all manifest source (docs/api/internal.md). */
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
