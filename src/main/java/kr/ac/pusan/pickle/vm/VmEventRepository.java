package kr.ac.pusan.pickle.vm;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only: only {@code save} and reads — no updates or deletes. */
public interface VmEventRepository extends JpaRepository<VmEvent, Long> {

    Page<VmEvent> findByVmIdOrderByIdDesc(Long vmId, Pageable pageable);
}
