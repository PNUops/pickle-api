package kr.ac.pusan.pickle.inventory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NodeRepository extends JpaRepository<Node, Long> {

    List<Node> findByStatusOrderByIdAsc(NodeStatus status);
}
