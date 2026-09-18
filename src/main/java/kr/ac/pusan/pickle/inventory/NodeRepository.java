package kr.ac.pusan.pickle.inventory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NodeRepository extends JpaRepository<Node, Long> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<Node> findByPublicId(UUID publicId);

    List<Node> findByStatusOrderByIdAsc(NodeStatus status);

    /** Serializes approval reservations on every eligible node in deterministic order. */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Node n where n.id in :ids order by n.id")
    List<Node> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);
}
