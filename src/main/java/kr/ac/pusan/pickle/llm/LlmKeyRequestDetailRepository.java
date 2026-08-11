package kr.ac.pusan.pickle.llm;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmKeyRequestDetailRepository extends JpaRepository<LlmKeyRequestDetail, Long> {

    Optional<LlmKeyRequestDetail> findByRequestId(long requestId);

    List<LlmKeyRequestDetail> findByRequestIdIn(List<Long> requestIds);
}
