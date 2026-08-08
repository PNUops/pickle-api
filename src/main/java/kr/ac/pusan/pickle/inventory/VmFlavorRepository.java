package kr.ac.pusan.pickle.inventory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VmFlavorRepository extends JpaRepository<VmFlavor, Long> {

    List<VmFlavor> findByStatusOrderByIdAsc(CatalogStatus status);

    boolean existsByName(String name);
}
