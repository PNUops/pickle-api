package kr.ac.pusan.pickle.orgs;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrgRepository extends JpaRepository<Org, Long> {

    Optional<Org> findBySlug(String slug);

    List<Org> findByStatusOrderByIdAsc(OrgStatus status);

    boolean existsBySlug(String slug);
}
