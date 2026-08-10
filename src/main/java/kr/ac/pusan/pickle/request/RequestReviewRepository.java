package kr.ac.pusan.pickle.request;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestReviewRepository extends JpaRepository<RequestReview, Long> {

    Optional<RequestReview> findByRequestId(Long requestId);

    List<RequestReview> findByRequestIdIn(Collection<Long> requestIds);
}
