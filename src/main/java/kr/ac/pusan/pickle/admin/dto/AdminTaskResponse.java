package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.provisioning.ProvisioningTask;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskKind;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskStatus;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.dto.ProvisioningTaskResponse;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code AdminTaskView}: {@code ProvisioningTaskView} plus the
 * VM/org context of the admin task queue. The join fields are defensively
 * nullable per contract (VM rows are permanent, so normally always present).
 */
public record AdminTaskResponse(
        Long taskId,
        Long vmId,
        @Nullable String vmName,
        @Nullable String hostname,
        @Nullable Long orgId,
        @Nullable String orgName,
        @Nullable String workspaceName,
        ProvisioningTaskKind kind,
        ProvisioningTaskStatus status,
        int currentStep,
        int totalSteps,
        String stepLabel,
        int attempts,
        @Nullable String lastError,
        @Nullable String jobrunrJobId,
        Instant createdAt,
        Instant updatedAt) {

    public static AdminTaskResponse from(ProvisioningTask task, Vm vm, String orgName,
            String workspaceName) {
        ProvisioningTaskResponse view = ProvisioningTaskResponse.from(task);
        return new AdminTaskResponse(task.getId(), task.getVmId(),
                vm == null ? null : vm.getName(),
                vm == null ? null : vm.getHostname(),
                vm == null ? null : vm.getOrgId(),
                orgName,
                workspaceName,
                view.kind(), view.status(), view.currentStep(), view.totalSteps(),
                view.stepLabel(), view.attempts(), view.lastError(),
                task.getJobrunrJobId(), task.getCreatedAt(), task.getUpdatedAt());
    }
}
