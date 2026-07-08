package kr.ac.pusan.pickle.ipam;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IpAllocationRepository extends JpaRepository<IpAllocation, Long> {

    List<IpAllocation> findByPoolIdAndStatus(Long poolId, AllocationStatus status);

    /**
     * The VM's live allocation. Crash guard for the pipeline's alloc step:
     * {@code IpamService.allocate} may have committed before the crash while
     * {@code vms.ip_allocation_id} was never written — the re-run reuses this
     * row instead of leaking a second address.
     */
    Optional<IpAllocation> findFirstByVmIdAndStatusOrderByIdDesc(Long vmId, AllocationStatus status);
}
