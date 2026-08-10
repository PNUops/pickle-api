package kr.ac.pusan.pickle.orgs;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrgRepository extends JpaRepository<Org, Long> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<Org> findByPublicId(UUID publicId);

    /**
     * Lookup by display name, for the dev seeder's find-or-create. Names carry
     * no uniqueness constraint, so the oldest match wins.
     */
    Optional<Org> findFirstByNameOrderByIdAsc(String name);

    List<Org> findByStatusOrderByIdAsc(OrgStatus status);

    List<Org> findByStatusAndHiddenFalseOrderByIdAsc(OrgStatus status);
}
