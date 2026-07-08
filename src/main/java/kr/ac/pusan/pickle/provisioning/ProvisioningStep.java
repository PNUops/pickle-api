package kr.ac.pusan.pickle.provisioning;

/**
 * The ten ordered steps of the provision pipeline (docs/plan/03).
 * {@code index} is what {@code provisioning_tasks.current_step} stores;
 * {@code label} is the Korean step name the contract exposes as
 * {@code ProvisioningTaskView.stepLabel}.
 */
public enum ProvisioningStep {

    GUARD(0, "준비 확인"),
    PLACE(1, "노드 배치"),
    ALLOC_IP(2, "IP 할당"),
    VMID(3, "VMID 발급"),
    CLONE(4, "템플릿 복제 중"),
    CONFIG(5, "초기 설정 중"),
    RESIZE(6, "디스크 확장 중"),
    START(7, "부팅·에이전트 대기"),
    VERIFY(8, "네트워크 확인"),
    FINALIZE(9, "마무리");

    public static final int TOTAL_STEPS = values().length;

    private final int index;
    private final String label;

    ProvisioningStep(int index, String label) {
        this.index = index;
        this.label = label;
    }

    public int index() {
        return index;
    }

    public String label() {
        return label;
    }

    /** The step for a {@code current_step} value; clamped for robustness. */
    public static ProvisioningStep of(int index) {
        ProvisioningStep[] steps = values();
        if (index < 0 || index >= steps.length) {
            return index < 0 ? GUARD : FINALIZE;
        }
        return steps[index];
    }
}
