package kr.ac.pusan.pickle.provisioning;

/**
 * Provisioning seam executed inside JobRunr workers (docs/plan/03: API
 * endpoints only write intent and enqueue; every Proxmox mutation happens in a
 * job). Approval enqueues {@code provisionVm(vmId)} against this interface, so
 * the stored job resolves whichever implementation is the current bean:
 * {@link MockProvisionVmJob} in M2, the real Proxmox pipeline in M3.
 *
 * <p>Implementations must be idempotent — a re-run of a finished or
 * half-finished job must be safe.</p>
 */
public interface ProvisioningService {

    void provisionVm(long vmId);
}
