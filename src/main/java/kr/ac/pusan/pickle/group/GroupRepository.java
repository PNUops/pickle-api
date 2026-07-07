package kr.ac.pusan.pickle.group;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupRepository extends JpaRepository<Group, Long> {

    boolean existsBySlug(String slug);
}
