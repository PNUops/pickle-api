package kr.ac.pusan.pickle.provisioning;

/**
 * Provisioning seam executed inside JobRunr workers (docs/plan/03: API
 * endpoints only write intent and enqueue; every Proxmox mutation happens in a
 * job). Approval enqueues {@code provisionVm(vmId)} against this interface, so
 * the stored job resolves whichever implementation is the current bean:
 * {@link ProvisionVmJob}, the real Proxmox pipeline, since M3 (the M2
 * MockProvisionVmJob is gone).
 *
 * <p>Implementations must be idempotent — a re-run of a finished or
 * half-finished job must be safe.</p>
 */
public interface ProvisioningService {

    // NOTE: put @Job(name/retries) on the *implementation* method, not here.
    // JobRunr stores the runtime class of the captured bean (verified against
    // 8.7.1: jobsignature = <ImplClass>.provisionVm(long)) and reads the
    // annotation from that class — an @Job placed on this interface method
    // is silently ignored.
    void provisionVm(long vmId);
}
