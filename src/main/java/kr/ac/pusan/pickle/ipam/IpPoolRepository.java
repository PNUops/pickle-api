package kr.ac.pusan.pickle.ipam;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IpPoolRepository extends JpaRepository<IpPool, Long> {

    Optional<IpPool> findByName(String name);
}
