package kr.ac.pusan.pickle.publishing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CertificateRepository extends JpaRepository<Certificate, Long> {

    /** The current cert for a custom domain (excludes revoked/archived). */
    Optional<Certificate> findFirstByDomainIdAndStatusNot(Long domainId, CertificateStatus status);

    /** The shared platform wildcard for a scope, e.g. {@code *.pickle.pnuops.com}. */
    Optional<Certificate> findFirstByKindAndScope(CertificateKind kind, String scope);

    List<Certificate> findByDomainId(Long domainId);

    /**
     * Admin listing. The shared wildcard ({@code domainId} null) is visible to
     * every admin; per-domain LE certs are org-scoped via domain→vm. Ordered by
     * soonest expiry so the impending-renewal view is the natural default. The
     * expiry cut-off is a separate query (below) so no query binds a typed null
     * (PostgreSQL cannot infer the type of a standalone {@code ? is null}).
     */
    @Query("""
            select c from Certificate c
              left join Domain d on d.id = c.domainId
              left join kr.ac.pusan.pickle.vm.Vm v on v.id = d.vmId
            where (:orgId is null or c.domainId is null or v.orgId = :orgId)
              and (:status is null or cast(c.status as string) = :status)
            order by c.notAfter asc nulls last, c.id desc
            """)
    Page<Certificate> findAdmin(@Param("orgId") Long orgId, @Param("status") String status,
            Pageable pageable);

    @Query("""
            select c from Certificate c
              left join Domain d on d.id = c.domainId
              left join kr.ac.pusan.pickle.vm.Vm v on v.id = d.vmId
            where (:orgId is null or c.domainId is null or v.orgId = :orgId)
              and (:status is null or cast(c.status as string) = :status)
              and c.notAfter <= :notAfter
            order by c.notAfter asc nulls last, c.id desc
            """)
    Page<Certificate> findAdminExpiring(@Param("orgId") Long orgId, @Param("status") String status,
            @Param("notAfter") Instant notAfter, Pageable pageable);
}
