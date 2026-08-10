package kr.ac.pusan.pickle.relay;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RelayRepository extends JpaRepository<Relay, Long> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<Relay> findByPublicId(UUID publicId);

    /** Allocation target: the lowest-id enabled relay (single relay today). */
    Optional<Relay> findFirstByEnabledTrueOrderByIdAsc();

    List<Relay> findAllByOrderByIdAsc();
}
