package kr.ac.pusan.pickle.inventory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VmTemplateRepository extends JpaRepository<VmTemplate, Long> {

    List<VmTemplate> findByStatusOrderByIdAsc(TemplateStatus status);
}
