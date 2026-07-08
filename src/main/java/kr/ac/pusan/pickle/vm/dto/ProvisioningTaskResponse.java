package kr.ac.pusan.pickle.vm.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.provisioning.ProvisioningTask;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskKind;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskStatus;

/**
 * Contract schema {@code ProvisioningTaskView}: progress of the in-flight or
 * last-failed async pipeline of a VM. Step labels are the user-facing Korean
 * names of the docs/plan/03 pipeline steps (0–9 for PROVISION); async
 * failures surface here (status/lastError), never as HTTP errors.
 */
public record ProvisioningTaskResponse(
        ProvisioningTaskKind kind,
        ProvisioningTaskStatus status,
        int currentStep,
        int totalSteps,
        String stepLabel,
        int attempts,
        String lastError,
        Instant updatedAt) {

    /** docs/plan/03 provision pipeline steps 0–9, in user-facing Korean. */
    private static final List<String> PROVISION_STEPS = List.of(
            "준비 확인 중",
            "노드 배치 중",
            "IP 할당 중",
            "VMID 발급 중",
            "템플릿 복제 중",
            "VM 설정 중",
            "디스크 크기 조정 중",
            "VM 시작 중",
            "네트워크 확인 중",
            "마무리 중");

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
