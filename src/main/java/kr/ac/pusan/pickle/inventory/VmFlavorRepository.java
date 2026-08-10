package kr.ac.pusan.pickle.inventory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VmFlavorRepository extends JpaRepository<VmFlavor, Long> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<VmFlavor> findByPublicId(UUID publicId);

    /**
     * Display order of the spec presets: smallest first, on the three numbers
     * that make a preset what it is, with the id as the last tie-break.
     *
     * <p>The presets have no family axis to group by; size is the axis the
     * student is choosing along, so the list reads as a scale instead of as the
     * order an operator happened to create them in. Ascending also puts the
     * modest preset in front of the generous one, which is the direction the
     * quota policy wants a hesitant requester nudged.</p>
     */
    Sort DISPLAY_ORDER = Sort.by("vcpu", "memoryMb", "diskGb", "id");

    default List<VmFlavor> findByStatusInDisplayOrder(CatalogStatus status) {
        return findByStatus(status, DISPLAY_ORDER);
    }

    default List<VmFlavor> findAllInDisplayOrder() {
        return findAll(DISPLAY_ORDER);
    }

    List<VmFlavor> findByStatus(CatalogStatus status, Sort sort);

    /** Rows of one status with no display intent — order carries no meaning here. */
    List<VmFlavor> findByStatus(CatalogStatus status);

    boolean existsByName(String name);
}
