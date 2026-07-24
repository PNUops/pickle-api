package kr.ac.pusan.pickle.vm.dto;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.provisioning.ProvisioningStep;
import kr.ac.pusan.pickle.provisioning.ProvisioningTask;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskKind;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code ProvisioningTaskView}: progress of the in-flight or
 * last-failed async pipeline of a VM. Step labels are the user-facing Korean
 * names of the provision pipeline steps (0–9 for PROVISION); async
 * failures surface here (status/lastError), never as HTTP errors.
 */
public record ProvisioningTaskResponse(
        ProvisioningTaskKind kind,
        ProvisioningTaskStatus status,
        int currentStep,
        int totalSteps,
        String stepLabel,
        int attempts,
        @Nullable String lastError,
        Instant updatedAt) {

    /** Provision pipeline steps 0–9 — the single source of the
     *  user-facing Korean labels is {@link ProvisioningStep}. */
    private static final List<String> PROVISION_STEPS =
            Arrays.stream(ProvisioningStep.values()).map(ProvisioningStep::label).toList();

    private static final Map<ProvisioningTaskKind, List<String>> STEP_LABELS = Map.of(
            ProvisioningTaskKind.PROVISION, PROVISION_STEPS,
            ProvisioningTaskKind.DELETE, List.of("종료 및 파기 중"),
            ProvisioningTaskKind.REINSTALL, List.of("재설치 중"));

    public static ProvisioningTaskResponse from(ProvisioningTask task) {
        List<String> labels = STEP_LABELS.get(task.getKind());
        int step = Math.clamp(task.getCurrentStep(), 0, labels.size() - 1);
        return new ProvisioningTaskResponse(task.getKind(), task.getStatus(),
                task.getCurrentStep(), labels.size(), labels.get(step), task.getAttempts(),
                task.getLastError(), task.getUpdatedAt());
    }
}
