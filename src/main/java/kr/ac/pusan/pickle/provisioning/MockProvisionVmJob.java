package kr.ac.pusan.pickle.provisioning;

import java.time.Duration;
import java.time.Instant;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * M2 mock provisioning: no Proxmox call — a short simulated delay, then the
 * CREATING → RUNNING transition (docs/plan/03 M2 boundary). Idempotent: the
 * transition is a compare-and-set on status CREATING, so re-runs (JobRunr
 * retries, duplicate enqueues) are no-ops once the VM moved on.
 */
@Component
public class MockProvisionVmJob implements ProvisioningService {

    static final String COMPLETED_DETAIL = "모의 프로비저닝 완료";

    private static final Logger log = LoggerFactory.getLogger(MockProvisionVmJob.class);
    private static final Duration SIMULATED_DELAY = Duration.ofMillis(200);

    private final VmRepository vmRepository;

    public MockProvisionVmJob(VmRepository vmRepository) {
        this.vmRepository = vmRepository;
    }

    @Override
    @Job(name = "mock-provision-vm %0", retries = 5)
    @Transactional
    public void provisionVm(long vmId) {
        // Missing row → exception → JobRunr retry; covers the (harmless) race
        // where the worker polls the job before the approve tx is visible.
        Vm vm = vmRepository.findById(vmId)
                .orElseThrow(() -> new IllegalStateException("VM " + vmId + " not found (approve tx not visible yet?)"));
        if (vm.getStatus() != VmStatus.CREATING) {
            log.info("Mock provisioning skipped for vm {} (status {})", vmId, vm.getStatus());
            return;
        }
        try {
            Thread.sleep(SIMULATED_DELAY.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Mock provisioning interrupted for vm " + vmId, e);
        }
        int updated = vmRepository.transitionStatus(vmId, VmStatus.CREATING, VmStatus.RUNNING,
                COMPLETED_DETAIL, Instant.now());
        if (updated == 1) {
            log.info("Mock provisioning completed for vm {} (CREATING → RUNNING)", vmId);
        } else {
            log.info("Mock provisioning lost the CAS for vm {} — already transitioned elsewhere", vmId);
        }
    }
}
