package kr.ac.pusan.pickle.request.vm;

import java.util.Collection;
import java.util.List;
import kr.ac.pusan.pickle.request.RequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VmRequestDetailRepository extends JpaRepository<VmRequestDetail, Long> {

    /** Detail rows for a page of requests, for batch assembly. */
    List<VmRequestDetail> findByRequestIdIn(Collection<Long> requestIds);

    /**
     * Whether a hostname is already spoken for by an undecided request.
     * Reads across both tables because the wish is on the detail row and the
     * status that makes it binding is on the request.
     */
    @Query("""
            select count(d) > 0 from VmRequestDetail d, Request r
             where d.requestId = r.id and d.desiredSlug = :slug and r.status = :status
            """)
    boolean existsByDesiredSlugAndRequestStatus(@Param("slug") String slug,
            @Param("status") RequestStatus status);
}
