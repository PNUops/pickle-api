package kr.ac.pusan.pickle.publishing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DomainRepository extends JpaRepository<Domain, Long> {

    /** The single live domain for a VM (v1: one publication per VM). */
    Optional<Domain> findFirstByVmIdAndStatusNotOrderByIdDesc(Long vmId, DomainStatus status);

    /** Final FQDN-uniqueness gate (partial unique index backs this). */
    boolean existsByFqdnAndStatusNot(String fqdn, DomainStatus status);

    /** Every domain row of a VM, any status — the deletion teardown sweep. */
    List<Domain> findByVmId(Long vmId);

    /** Custom domains due for DNS re-check (recurring verification scan). */
    List<Domain> findByKindAndStatusIn(DomainKind kind, Collection<DomainStatus> statuses);

    /**
     * User listing — scoped to the caller's own groups' VMs. {@code vmId} and
     * {@code status} are optional filters; newest first.
     */
    @Query("""
            select d from Domain d join kr.ac.pusan.pickle.vm.Vm v on v.id = d.vmId
            where v.groupId in :groupIds
              and (:vmId is null or d.vmId = :vmId)
              and (:status is null or cast(d.status as string) = :status)
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
