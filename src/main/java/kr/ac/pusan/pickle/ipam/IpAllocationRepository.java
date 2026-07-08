package kr.ac.pusan.pickle.ipam;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IpAllocationRepository extends JpaRepository<IpAllocation, Long> {

    List<IpAllocation> findByPoolIdAndStatus(Long poolId, AllocationStatus status);
}
