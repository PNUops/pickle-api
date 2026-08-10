package kr.ac.pusan.pickle.vm;

import org.springframework.data.domain.Page;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only: only {@code save} and reads — no updates or deletes. */
public interface VmEventRepository extends JpaRepository<VmEvent, Long> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<VmEvent> findByPublicId(UUID publicId);

    Page<VmEvent> findByVmIdOrderByIdDesc(Long vmId, Pageable pageable);
}
