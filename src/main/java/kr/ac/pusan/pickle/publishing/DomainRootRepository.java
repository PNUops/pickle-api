package kr.ac.pusan.pickle.publishing;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainRootRepository extends JpaRepository<DomainRoot, Long> {

    /** The root by its DNS name; empty when this platform issues nothing there. */
    Optional<DomainRoot> findByRootDomain(String rootDomain);
}
