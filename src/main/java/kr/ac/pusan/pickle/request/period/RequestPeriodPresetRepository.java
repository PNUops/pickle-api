package kr.ac.pusan.pickle.request.period;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestPeriodPresetRepository extends JpaRepository<RequestPeriodPreset, Long> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<RequestPeriodPreset> findByPublicId(UUID publicId);

    /**
     * Display order of the periods on offer.
     *
     * <p>There is no arithmetic that orders a term against a vacation against
     * an indefinite period, and sorting by end date would put next year's term
     * behind this year's vacation in a list nobody reads that way. So the order
     * is stated rather than derived, and the admin screen is where it is
     * stated. The id is the tie-break so the list never wobbles between
     * requests.</p>
     */
    Sort DISPLAY_ORDER = Sort.by("displayOrder", "id");

    List<RequestPeriodPreset> findByStatus(CatalogStatus status, Sort sort);

    default List<RequestPeriodPreset> findByStatusInDisplayOrder(CatalogStatus status) {
        return findByStatus(status, DISPLAY_ORDER);
    }

    default List<RequestPeriodPreset> findAllInDisplayOrder() {
        return findAll(DISPLAY_ORDER);
    }

    boolean existsByName(String name);
}
