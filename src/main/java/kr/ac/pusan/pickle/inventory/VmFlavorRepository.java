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
     * Display order of the specs, stated rather than derived.
     *
     * <p>Sorting by size was right while the specs were a ladder from small to
     * large. They are shapes now -- one leaning on cores, one on memory -- and
     * no arithmetic says which of those comes first. So the order is a column
     * the admin screen writes, with the id as the tie-break so the list never
     * wobbles between requests.</p>
     */
    Sort DISPLAY_ORDER = Sort.by("displayOrder", "id");

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
