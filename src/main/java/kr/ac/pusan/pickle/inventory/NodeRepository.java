package kr.ac.pusan.pickle.inventory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NodeRepository extends JpaRepository<Node, Long> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<Node> findByPublicId(UUID publicId);

    List<Node> findByStatusOrderByIdAsc(NodeStatus status);
}
