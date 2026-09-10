package kr.ac.pusan.pickle.publishing;

import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DomainRecordRepository extends JpaRepository<DomainRecord, Long> {

    /** Every row of a domain, including the ones on their way out of the zone. */
    List<DomainRecord> findByDomainId(Long domainId);

    /**
     * The sets a domain currently claims — what an edit is diffed against and
     * what a reader is shown. A row on its way out is not among them: it is
     * already gone as far as its owner is concerned, and only the zone has yet
     * to be told.
     */
    List<DomainRecord> findByDomainIdAndStatusNot(Long domainId, DomainRecordStatus status);

    /**
     * Every domain that has a set the zone has not confirmed, whatever the
     * state of the domain row itself.
     *
     * <p>Asked of the records rather than of the domains on purpose. A domain
     * can be retired while its sets are still standing — the admin takedown
     * frees the name in the same transaction that marks them for removal — and
     * a retired row is invisible to every scan that starts from
     * {@code domains}. What the zone is owed is recorded here, so this is what
     * has to be asked.</p>
     */
    @Query("select distinct r.domainId from DomainRecord r where r.status <> :applied")
    List<Long> findDomainIdsOwedTheZone(@Param("applied") DomainRecordStatus applied);

    /**
     * The rows an apply has to act on, taken under their locks so a second
     * apply for the same domain waits rather than pushing the same set twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DomainRecord r where r.domainId = :domainId order by r.id")
    List<DomainRecord> findByDomainIdForUpdate(@Param("domainId") Long domainId);
}
