package kr.ac.pusan.pickle.ipam;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IpPoolRepository extends JpaRepository<IpPool, Long> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<IpPool> findByPublicId(UUID publicId);

    Optional<IpPool> findByName(String name);
}
