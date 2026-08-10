package kr.ac.pusan.pickle.ipam;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface IpAllocationRepository
        extends JpaRepository<IpAllocation, Long>, JpaSpecificationExecutor<IpAllocation> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<IpAllocation> findByPublicId(UUID publicId);

    /**
     * The VM's live allocation. Crash guard for the pipeline's alloc step:
     * {@code IpamService.allocate} may have committed before the crash while
     * {@code vms.ip_allocation_id} was never written — the re-run reuses this
     * row instead of leaking a second address.
     */
    Optional<IpAllocation> findFirstByVmIdAndStatusOrderByIdDesc(Long vmId, AllocationStatus status);
}
