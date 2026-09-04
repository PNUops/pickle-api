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
     * <p>The catalogue is a handful of named periods a requester picks from, and
     * the order they should read in is the academic one an operator has in mind
     * when registering them. No column carries that: end date sorts
     * chronologically, which splits a term from the vacation that belongs with
     * it, and nothing else is comparable at all. So the order is stated rather
     * than derived, and the admin screen is where it is stated. The id is the
     * tie-break so the list never wobbles between requests.</p>
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
