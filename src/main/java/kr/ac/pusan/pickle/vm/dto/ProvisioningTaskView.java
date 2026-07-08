package kr.ac.pusan.pickle.vm.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.provisioning.ProvisioningStep;
import kr.ac.pusan.pickle.provisioning.ProvisioningTask;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskKind;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskStatus;

/**
 * Contract schema {@code ProvisioningTaskView}: progress of the VM's most
 * recent async task. Async failures (IP pool exhausted, Proxmox errors …)
 * surface here as {@code NEEDS_ADMIN}/{@code FAILED} + {@code lastError},
 * never as HTTP errors (contract v0.3.1).
 */
public record ProvisioningTaskView(
        ProvisioningTaskKind kind,
        ProvisioningTaskStatus status,
        int currentStep,
        int totalSteps,
        String stepLabel,
        int attempts,
        String lastError,
        Instant updatedAt) {

    public static ProvisioningTaskView from(ProvisioningTask task) {
        return new ProvisioningTaskView(task.getKind(), task.getStatus(), task.getCurrentStep(),
                ProvisioningStep.TOTAL_STEPS, ProvisioningStep.of(task.getCurrentStep()).label(),
                task.getAttempts(), task.getLastError(), task.getUpdatedAt());
    }
}
