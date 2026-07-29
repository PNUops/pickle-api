package kr.ac.pusan.pickle.inventory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OsImageRepository extends JpaRepository<OsImage, Long> {

    List<OsImage> findByStatusOrderByIdAsc(TemplateStatus status);

    /**
     * Whether a node hosts a usable copy of an OS image. Image rows are
     * per-node (V3); a node "has" the image when it carries a row of the
     * same {@code name} in the given status — the same name-based match node
     * placement uses.
     */
    boolean existsByNameAndNodeIdAndStatus(String name, Long nodeId, TemplateStatus status);
}
