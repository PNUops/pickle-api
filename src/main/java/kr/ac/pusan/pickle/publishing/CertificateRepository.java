package kr.ac.pusan.pickle.publishing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CertificateRepository extends JpaRepository<Certificate, Long> {

    /** The current cert for a custom domain (excludes revoked/archived). */
    Optional<Certificate> findFirstByDomainIdAndStatusNot(Long domainId, CertificateStatus status);

    /**
     * The live platform wildcard for a root's scope, e.g. {@code *.pusan.dev}.
     * Reads exactly the rows the partial unique index covers, so the row this
     * returns is the row uniqueness is enforced on.
     */
    default Optional<Certificate> findLiveWildcard(CertificateKind kind, String scope) {
        return findLatestSharedByKindAndScopeExcludingStatus(kind, scope,
                CertificateStatus.REVOKED, Limit.of(1));
    }

    /**
     * Backs {@link #findLiveWildcard}. The unique index on the shared wildcard is
     * narrowed to non-revoked rows with no domain, because rotation and manual
     * revocation leave the old row behind; a read wider than that region can
     * return a superseded certificate while a valid one exists, which surfaces as
     * a refused publish and an expired date on screen. Uniqueness leaves at most
     * one row here, so the ordering only decides a tie the index cannot see:
     * latest expiry wins, and a row still being issued ({@code notAfter} null)
     * sorts last so it never displaces a certificate that is currently valid.
     */
    @Query("""
            select c from Certificate c
            where c.kind = :kind
              and c.scope = :scope
              and c.domainId is null
              and c.status <> :excludedStatus
            order by c.notAfter desc nulls last, c.id desc
            """)
    Optional<Certificate> findLatestSharedByKindAndScopeExcludingStatus(
            @Param("kind") CertificateKind kind, @Param("scope") String scope,
            @Param("excludedStatus") CertificateStatus excludedStatus, Limit limit);

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
