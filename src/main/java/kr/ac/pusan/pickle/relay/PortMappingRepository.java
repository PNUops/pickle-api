package kr.ac.pusan.pickle.relay;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PortMappingRepository
        extends JpaRepository<PortMapping, Long>, JpaSpecificationExecutor<PortMapping> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<PortMapping> findByPublicId(UUID publicId);

    List<PortMapping> findByVmIdOrderByIdAsc(long vmId);

    Optional<PortMapping> findByIdAndVmId(long id, long vmId);

    long countByRelayId(long relayId);
}
