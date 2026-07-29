package kr.ac.pusan.pickle.relay;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RelayRepository extends JpaRepository<Relay, Long> {

    /** Allocation target: the lowest-id enabled relay (single relay today). */
    Optional<Relay> findFirstByEnabledTrueOrderByIdAsc();

    List<Relay> findAllByOrderByIdAsc();
}
