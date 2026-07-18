package kr.ac.pusan.pickle.group;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupRepository extends JpaRepository<Group, Long> {

    /**
     * Slug uniqueness is over live rows only (V34 partial unique index), so a
     * deleted group's slug can be reused (contract: deleteGroup).
     */
    boolean existsBySlugAndDeletedAtIsNull(String slug);

    /** A non-deleted group by id — deleted groups answer empty (masked 404). */
    Optional<Group> findByIdAndDeletedAtIsNull(Long id);

    /** Group targeting / existence check that excludes soft-deleted groups. */
    boolean existsByIdAndDeletedAtIsNull(Long id);
}
