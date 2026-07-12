package kr.ac.pusan.pickle.ipam;

import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for "the VM's <b>own</b> live IP" (docs/plan/07 SSRF
 * rule: a route/proxy target is forced to the VM's own address). Resolving by
 * allocation id alone is not enough — a stale pointer left by a crashed release
 * can point at an allocation that has since been released or re-claimed by a
 * different VM. This method only returns an address when the allocation is still
 * {@link AllocationStatus#ALLOCATED} <b>and</b> still owned by the asking VM, so
 * a stale/reclaimed pointer can never yield another VM's IP.
 *
 * <p>Shared by the user-facing detail view ({@code VmQueryService}) and the
 * internal SSH-gateway route lookup ({@code SshGatewayRouteService}).</p>
 */
@Component
public class IpAddressResolver {

    private final IpAllocationRepository ipAllocationRepository;

    public IpAddressResolver(IpAllocationRepository ipAllocationRepository) {
        this.ipAllocationRepository = ipAllocationRepository;
    }

    /**
     * The host address (no prefix) of the VM's live, owned allocation, or
     * {@code null} when there is no such allocation (missing pointer, released,
     * quarantined, or re-claimed by another VM).
     *
     * @param ipAllocationId the VM's {@code ip_allocation_id} pointer (nullable)
     * @param vmId           the VM the address must still belong to
     */
    public String liveHostIp(Long ipAllocationId, Long vmId) {
        if (ipAllocationId == null) {
            return null;
        }
        return ipAllocationRepository.findById(ipAllocationId)
                .filter(allocation -> allocation.getStatus() == AllocationStatus.ALLOCATED)
                .filter(allocation -> Objects.equals(allocation.getVmId(), vmId))
                .map(IpAllocation::getIp)
                .map(IpAddressResolver::stripPrefix)
                .orElse(null);
    }

    private static String stripPrefix(String ip) {
        int slash = ip.indexOf('/');
        return slash >= 0 ? ip.substring(0, slash) : ip;
    }
}
