package kr.ac.pusan.pickle.vmrequest;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VmRequestReviewRepository extends JpaRepository<VmRequestReview, Long> {

    Optional<VmRequestReview> findByRequestId(Long requestId);

    List<VmRequestReview> findByRequestIdIn(Collection<Long> requestIds);
}
