package kr.ac.pusan.pickle.campusip;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CampusIpRequestRepository
        extends JpaRepository<CampusIpRequest, Long>, JpaSpecificationExecutor<CampusIpRequest> {

    List<CampusIpRequest> findByVmIdOrderByIdDesc(long vmId);

    Optional<CampusIpRequest> findByIdAndVmId(long id, long vmId);

    boolean existsByVmIdAndStatusIn(long vmId, Collection<CampusIpRequestStatus> statuses);
}
