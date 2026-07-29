package kr.ac.pusan.pickle.relay;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PortMappingRepository
        extends JpaRepository<PortMapping, Long>, JpaSpecificationExecutor<PortMapping> {

    List<PortMapping> findByVmIdOrderByIdAsc(long vmId);

    Optional<PortMapping> findByIdAndVmId(long id, long vmId);

    long countByRelayId(long relayId);
}
